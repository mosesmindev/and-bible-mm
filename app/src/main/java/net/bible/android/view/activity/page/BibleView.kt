/*
 * Copyright (c) 2020 Martin Denham, Tuomas Airaksinen and the And Bible contributors.
 *
 * This file is part of And Bible (http://github.com/AndBible/and-bible).
 *
 * And Bible is free software: you can redistribute it and/or modify it under the
 * terms of the GNU General Public License as published by the Free Software Foundation,
 * either version 3 of the License, or (at your option) any later version.
 *
 * And Bible is distributed in the hope that it will be useful, but WITHOUT ANY WARRANTY;
 * without even the implied warranty of MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.
 * See the GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License along with And Bible.
 * If not, see http://www.gnu.org/licenses/.
 *
 */

package net.bible.android.view.activity.page

import android.annotation.SuppressLint
import android.content.pm.ApplicationInfo
import android.os.Looper
import android.util.Log
import android.view.ContextMenu.ContextMenuInfo
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.View
import android.webkit.ConsoleMessage
import android.webkit.JsResult
import android.webkit.WebChromeClient
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.core.view.GestureDetectorCompat
import kotlinx.serialization.Serializable
import kotlinx.serialization.serializer
import net.bible.android.BibleApplication
import net.bible.android.activity.R
import net.bible.android.control.bookmark.BookmarkControl
import net.bible.android.control.event.ABEventBus
import net.bible.android.control.event.window.CurrentWindowChangedEvent
import net.bible.android.control.event.window.NumberOfWindowsChangedEvent
import net.bible.android.control.event.window.ScrollSecondaryWindowEvent
import net.bible.android.control.event.window.WindowSizeChangedEvent
import net.bible.android.control.link.LinkControl
import net.bible.android.control.page.ChapterVerse
import net.bible.android.control.page.CurrentBiblePage
import net.bible.android.control.page.PageControl
import net.bible.android.control.page.PageTiltScrollControl
import net.bible.android.control.page.window.DecrementBusyCount
import net.bible.android.control.page.window.IncrementBusyCount
import net.bible.android.control.page.window.Window
import net.bible.android.control.page.window.WindowControl
import net.bible.android.control.versification.toV11n
import net.bible.android.database.WorkspaceEntities
import net.bible.android.database.bookmarks.BookmarkEntities
import net.bible.android.database.json
import net.bible.android.view.activity.base.DocumentView
import net.bible.android.view.activity.base.SharedActivityState
import net.bible.android.view.activity.page.actionmode.VerseActionModeMediator
import net.bible.android.view.activity.page.screen.AfterRemoveWebViewEvent
import net.bible.android.view.activity.page.screen.PageTiltScroller
import net.bible.android.view.activity.page.screen.WebViewsBuiltEvent
import net.bible.android.view.util.UiUtils
import net.bible.service.common.CommonUtils
import net.bible.service.device.ScreenSettings
import org.crosswire.jsword.book.BookCategory
import org.crosswire.jsword.passage.KeyUtil
import org.crosswire.jsword.passage.Verse
import java.lang.ref.WeakReference

/** The WebView component that shows the main bible and commentary text
 *
 * @author Martin Denham [mjdenham at gmail dot com]
 */

/**
 * Constructor.  This version is only needed if you will be instantiating
 * the object manually (not from a layout XML file).
 */

@SuppressLint("ViewConstructor")
class BibleView(val mainBibleActivity: MainBibleActivity,
                internal var windowRef: WeakReference<Window>,
                private val windowControl: WindowControl,
                private val bibleKeyHandler: BibleKeyHandler,
                private val pageControl: PageControl,
                private val pageTiltScrollControl: PageTiltScrollControl,
                private val linkControl: LinkControl,
                private val bookmarkControl: BookmarkControl
) :
        WebView(mainBibleActivity.applicationContext),
        DocumentView,
        VerseActionModeMediator.VerseHighlightControl
{

    private var contextMenuInfo: BibleViewContextMenuInfo? = null

    private lateinit var bibleJavascriptInterface: BibleJavascriptInterface

    private lateinit var pageTiltScroller: PageTiltScroller
    private var hideScrollBar: Boolean = false

    private var wasAtRightEdge: Boolean = false
    private var wasAtLeftEdge: Boolean = false

    internal var minChapter = -1
    internal var maxChapter = -1

    //private var loadedChapters = mutableSetOf<Int>()


    private var gestureDetector: GestureDetectorCompat

    /** Used to prevent scroll off bottom using auto-scroll
     * see http://stackoverflow.com/questions/5069765/android-webview-how-to-autoscroll-a-page
     */
    //TODO get these once, they probably won't change
    private val maxVerticalScroll: Int
        get() = computeVerticalScrollRange() - computeVerticalScrollExtent()

    private val maxHorizontalScroll: Int
        get() = computeHorizontalScrollRange() - computeHorizontalScrollExtent()

    private val gestureListener  = BibleGestureListener(mainBibleActivity)

    private var toBeDestroyed = false
    private var lastestXml: String = ""
    private var needsOsisContent: Boolean = false
    private var htmlLoadingOngoing: Boolean = false
        set(value) {
            if(value != field) {
                ABEventBus.getDefault().post(if(value) IncrementBusyCount() else DecrementBusyCount())
            }
            field = value
        }

    var window: Window
        get() = windowRef.get()!!
        set(value) {
            windowRef = WeakReference(value)
        }

    class BibleViewTouched(val onlyTouch: Boolean = false)

    init {
        if (0 != BibleApplication.application.applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE) {
            setWebContentsDebuggingEnabled(true)
        }
        gestureDetector = GestureDetectorCompat(context, gestureListener)
        setOnTouchListener { v, event ->
            if (gestureDetector.onTouchEvent(event)) {
                true
            } else v.performClick()
        }
    }

    /**
     * This is not passed into the constructor due to a cyclic dependency. bjsi ->
     */
    fun setBibleJavascriptInterface(bibleJavascriptInterface: BibleJavascriptInterface) {
        this.bibleJavascriptInterface = bibleJavascriptInterface
        addJavascriptInterface(bibleJavascriptInterface, "android")
    }

    @SuppressLint("SetJavaScriptEnabled")
    fun initialise() {
        Log.d(TAG, "initialise")
        /* WebViewClient must be set BEFORE calling loadUrl! */
        webViewClient = object : WebViewClient() {
            override fun shouldOverrideUrlLoading(view: WebView, url: String): Boolean {
                // load Strongs refs when a user clicks on a link
                val loaded = linkControl.loadApplicationUrl(url)

                if(loaded) {
                    gestureListener.setDisableSingleTapOnce(true)
                    super.shouldOverrideUrlLoading(view, url)
                    return true
                }
                else {
                    return super.shouldOverrideUrlLoading(view, url)
                }
            }

            override fun onLoadResource(view: WebView, url: String) {
                Log.d(TAG, "onLoadResource:$url")
                super.onLoadResource(view, url)
            }

            override fun onReceivedError(view: WebView, errorCode: Int, description: String, failingUrl: String) {
                super.onReceivedError(view, errorCode, description, failingUrl)
                Log.e(TAG, description)
            }
        }

        // handle alerts
        webChromeClient = object : WebChromeClient() {
            override fun onJsAlert(view: WebView, url: String, message: String, result: JsResult): Boolean {
                Log.d(TAG, message)
                result.confirm()
                return true
            }

            override fun onConsoleMessage(consoleMessage: ConsoleMessage): Boolean {
                Log.d(TAG, "JS console ${consoleMessage.messageLevel()}: ${consoleMessage.message()}")
                return true
            }
        }

        // need javascript to enable jump to anchors/verses
        settings.javaScriptEnabled = true

        applyPreferenceSettings()

        pageTiltScroller = PageTiltScroller(this, pageTiltScrollControl)
        pageTiltScroller.enableTiltScroll(true)

        // if this webview becomes (in)active then must start/stop auto-scroll
        listenEvents = true

        htmlLoadingOngoing = true;
        loadUrl("file:///android_asset/bibleview-js/index.html")
    }

    override fun destroy() {
        toBeDestroyed = true
        pageTiltScroller.destroy()
        removeJavascriptInterface("android")
    }

    var listenEvents: Boolean = false
        set(value) {
            if(value == field) return
            if(value) {
                ABEventBus.getDefault().register(this)
            } else {
                ABEventBus.getDefault().unregister(this)
            }
            field = value
        }

    fun doDestroy() {
        if(!toBeDestroyed) {
            destroy()
        }
        listenEvents = false
        Log.d(TAG, "Destroying Bibleview")
        super.destroy()
        val win = windowRef.get()
        if(win != null && win.bibleView === this) {
            win.bibleView = null
        }
        onDestroy?.invoke()
    }

    /** apply settings set by the user using Preferences
     */
    override fun applyPreferenceSettings() {
        Log.d(TAG, "applyPreferenceSettings")
        applyFontSize()
    }

    private fun applyFontSize() {
        Log.d(TAG, "applyFontSize")
        val fontSize = pageControl.getDocumentFontSize(window)
        val oldFontSize = settings.defaultFontSize
        val fontFamily = window.pageManager.actualTextDisplaySettings.font!!.fontFamily!!
        settings.defaultFontSize = fontSize
        settings.standardFontFamily = fontFamily
        if(oldFontSize != fontSize) {
            doCheckWindows()
        }
    }

    /** may need updating depending on environmental brightness
     */
    override fun updateBackgroundColor() {
        Log.d(TAG, "updateBackgroundColor")
        setBackgroundColor(backgroundColor)
    }

    val backgroundColor: Int get() {
        val colors = window.pageManager.actualTextDisplaySettings.colors
        return (if(ScreenSettings.nightMode) colors?.nightBackground else colors?.dayBackground) ?: UiUtils.bibleViewDefaultBackgroundColor
    }

    var lastUpdated = 0L
    var latestBookmarks: List<BookmarkEntities.Bookmark> = emptyList()
    var bookmarkLabels: List<BookmarkEntities.Label> = emptyList()

    fun show(xml: String,
             bookmarks: List<BookmarkEntities.Bookmark>,
             updateLocation: Boolean = false,
             verse: Verse? = null,
             yOffsetRatio: Float? = null)
    {
        synchronized(this) {
            // set background colour if necessary
            updateBackgroundColor()

            // call this from here because some documents may require an adjusted font size e.g. those using Greek font
            applyFontSize()

            //val startPaddingHeight = (
            //    mainBibleActivity.topOffset2
            //        / mainBibleActivity.resources.displayMetrics.density
            //        // Add some extra extra so that infinite scrolling can activate
            //        + 20)
            //finalXml = finalXml.replace("<div id='start'>", "<div id='start' style='height:${startPaddingHeight}px'>")

            val currentPage = window.pageManager.currentPage
            bookmarkLabels = bookmarkControl.allLabels

            //var jumpToChapterVerse = chapterVerse
            initialVerse = verse
            var jumpToYOffsetRatio = yOffsetRatio

            if (lastUpdated == 0L || updateLocation) {
                if (currentPage is CurrentBiblePage) {
                    //jumpToChapterVerse = window.pageManager.currentBible.currentChapterVerse
                    initialVerse = KeyUtil.getVerse(window.pageManager.currentBible.currentBibleVerse.verse)
                    //jumpToVerse = KeyUtil.getVerse(window.pageManager.currentBible.key)
                } else {
                    jumpToYOffsetRatio = currentPage.currentYOffsetRatio
                }
            }

            // either enable verse selection or the default text selection
            enableSelection()

            // allow zooming if map
            enableZoomForMap(pageControl.currentPageManager.isMapShown)

            contentVisible = false
            //loadedChapters.clear()

            val chapter = initialVerse?.chapter
            if (chapter != null) {
                addChapter(chapter)
                //loadedChapters.add(chapter)
            }

            //val jumpId = jumpToChapterVerse?.let { "'${getIdToJumpTo(it)}'" }

            //val settingsString = "{jumpToChapterVerse: $jumpId, " +
            //    "jumpToYOffsetRatio: $jumpToYOffsetRatio, " +
            //    "toolBarOffset: $toolbarOffset," +
            //    "displaySettings: $displaySettingsJson}"

            //val actualSettingsJson = window.pageManager.actualTextDisplaySettings.toJson()
            Log.d(TAG, "Show $initialVerse, $jumpToYOffsetRatio Window:$window, settings: toolbarOFfset:${toolbarOffset}, \n actualSettings: ${displaySettings.toJson()}")

            //finalHtml = finalHtml.replace("INITIALIZE_SETTINGS", settingsString)
            latestBookmarks = bookmarks
            lastestXml = xml
        }
        if(!htmlLoadingOngoing) {
            replaceOsis()
        } else {
            synchronized(this) {
                needsOsisContent = true
            }
        }
    }

    private fun addChapter(chapter: Int) {
        if(chapter < minChapter) {
            minChapter = chapter
        } else if(chapter > maxChapter) {
            maxChapter = chapter
        } else {
            Log.e(TAG, "Chapter already included")
        }
    }

    private var initialVerse: Verse? = null
    private val displaySettings get() = window.pageManager.actualTextDisplaySettings

    fun updateTextDisplaySettings() {
        Log.d(TAG, "updateTextDisplaySettings")
        updateBackgroundColor()
        applyFontSize()
        executeJavascriptOnUiThread("bibleView.setConfig(${displaySettings.toJson()});")

    }

    @Serializable
    data class ClientBookmark(val id: Long, val range: List<Int>, val labels: List<Long>)


    @Serializable
    data class ClientBookmarkStyle(val color: List<Int>)

    @Serializable
    data class ClientBookmarkLabel(val id: Long, val style: ClientBookmarkStyle?)


    private fun replaceOsis() {
        var xml = ""
        synchronized(this) {
            xml = lastestXml
            //StringEscapeUtils.escapeEcmaScript(xml)
            needsOsisContent = false
            contentVisible = true
            minChapter = initialVerse?.chapter ?: -1
            maxChapter = initialVerse?.chapter ?: -1
        }

        val bookmarkLabels = json.encodeToString(serializer(), bookmarkLabels.map {
            ClientBookmarkLabel(it.id, it.bookmarkStyle?.let { v -> ClientBookmarkStyle(v.colorArray) })
        })
        val bookmarks = json.encodeToString(serializer(), latestBookmarks.map {
            val labels = bookmarkControl.labelsForBookmark(it).toMutableList()
            if(labels.isEmpty())
                labels.add(bookmarkControl.LABEL_UNLABELLED)
            ClientBookmark(it.id, arrayListOf(it.ordinalStart, it.ordinalEnd), labels.map { it.id } )
        })

        executeJavascriptOnUiThread("""
            bibleView.setTitle("BibleView-${window.id}");
            bibleView.setConfig(${displaySettings.toJson()});
            bibleView.replaceOsis({
                key: ${initialVerse?.chapter},
                content: `$xml`,
                bookmarks: $bookmarks,
                bookmarkLabels: $bookmarkLabels,
            });
            bibleView.setupContent({
                jumpToOrdinal: ${initialVerse?.ordinal}, 
                jumpToYOffsetRatio: null,
                toolBarOffset: $toolbarOffset,
            });            
            """.trimIndent()
        )
    }

    /**
     * Enable or disable zoom controls depending on whether map is currently shown
     */
    private fun enableZoomForMap(isMap: Boolean) {
        Log.d(TAG, "enableZoomForMap $isMap")
        settings.builtInZoomControls = true
        settings.setSupportZoom(isMap)
        settings.displayZoomControls = false
        // http://stackoverflow.com/questions/3808532/how-to-set-the-initial-zoom-width-for-a-webview
        settings.loadWithOverviewMode = isMap
        //settings.useWideViewPort = isMap
    }


    var contentVisible = false

    /** prevent swipe right if the user is scrolling the page right  */
    override val isPageNextOkay: Boolean get () {
        var isOkay = true
        if (window.pageManager.isMapShown) {
            // allow swipe right if at right side of map
            val isAtRightEdge = scrollX >= maxHorizontalScroll

            // the first side swipe takes us to the edge and second takes us to next page
            isOkay = isAtRightEdge && wasAtRightEdge
            wasAtRightEdge = isAtRightEdge
            wasAtLeftEdge = false
        }
        return isOkay
    }

    /** prevent swipe left if the user is scrolling the page left  */
    override val isPagePreviousOkay: Boolean get () {
        var isOkay = true
        if (window.pageManager.isMapShown) {
            // allow swipe left if at left edge of map
            val isAtLeftEdge = scrollX == 0

            // the first side swipe takes us to the edge and second takes us to next page
            isOkay = isAtLeftEdge && wasAtLeftEdge
            wasAtLeftEdge = isAtLeftEdge
            wasAtRightEdge = false
        }
        return isOkay
    }

    override fun onWindowFocusChanged(hasWindowFocus: Boolean) {
        super.onWindowFocusChanged(hasWindowFocus)
        Log.d(TAG, "Focus changed so start/stop scroll $hasWindowFocus")
        if (hasWindowFocus) {
            resumeTiltScroll()
        } else {
            pauseTiltScroll()
        }
    }

    private fun pauseTiltScroll() {
        pageTiltScroller.enableTiltScroll(false)
    }

    private fun resumeTiltScroll() {
        // but if multiple windows then only if the current active window
        if (windowControl.isActiveWindow(window)) {
            pageTiltScroller.enableTiltScroll(true)
        }
    }

    /** ensure auto-scroll does not continue when screen is powered off
     */
    override fun onScreenTurnedOn() {
        resumeTiltScroll()
    }

    override fun onScreenTurnedOff() {
        pauseTiltScroll()
    }

    @SuppressLint("ClickableViewAccessibility")
    override fun onTouchEvent(event: MotionEvent): Boolean {
        //Log.d(TAG, "BibleView onTouchEvent");
        windowControl.activeWindow = window

        val handled = super.onTouchEvent(event)

        // Allow user to redefine viewing angle by touching screen
        pageTiltScroller.recalculateViewingPosition()

        return handled
    }

    override val currentPosition: Float get () {
        // see http://stackoverflow.com/questions/1086283/getting-document-position-in-a-webview
        return scrollY.toFloat() / contentHeight.toFloat()
    }

    override fun onKeyUp(keyCode: Int, event: KeyEvent): Boolean {
        //TODO allow DPAD_LEFT to always change page and navigation between links using dpad
        // placing BibleKeyHandler second means that DPAD left is unable to move to prev page if strongs refs are shown
        // vice-versa (webview second) means right & left can not be used to navigate between Strongs links

        // common key handling i.e. KEYCODE_DPAD_RIGHT & KEYCODE_DPAD_LEFT to change chapter
        return if (bibleKeyHandler.onKeyUp(keyCode, event)) {
            true
        } else super.onKeyUp(keyCode, event)

        // allow movement from link to link in current page
    }

    fun scroll(forward: Boolean, scrollAmount: Int): Boolean {
        var ok = false
        hideScrollBar = true
        for (i in 0 until scrollAmount) {
            if (forward) {
                // scroll down/forward if not at bottom
                if (scrollY + 1 < maxVerticalScroll) {
                    scrollBy(0, 1)
                    ok = true
                }
            } else {
                // scroll up/backward if not at top
                if (scrollY > TOP_OF_SCREEN) {
                    // scroll up/back
                    scrollBy(0, -1)
                    ok = true
                }
            }
        }
        hideScrollBar = false
        return ok
    }

    /** allow vertical scroll bar to be hidden during auto-scroll
     */
    override fun awakenScrollBars(startDelay: Int, invalidate: Boolean): Boolean {
        return if (!hideScrollBar) {
            super.awakenScrollBars(startDelay, invalidate)
        } else {
            false
        }
    }

    override fun asView(): View {
        return this
    }

    fun onEvent(event: CurrentWindowChangedEvent) {
        if (window == event.activeWindow) {
            bibleJavascriptInterface.notificationsEnabled = true
            resumeTiltScroll()
        } else {
            bibleJavascriptInterface.notificationsEnabled = false
            pauseTiltScroll()
        }
    }

    fun onEvent(event: ScrollSecondaryWindowEvent) {
        if (window == event.window) {
            scrollOrJumpToVerseOnUIThread(event.verse)
        }
    }

    private var checkWindows = false

    fun onEvent(event: MainBibleActivity.ConfigurationChanged) {
        checkWindows = true
    }

    fun onEvent(event: NumberOfWindowsChangedEvent) {
        if(window.isVisible)
            executeJavascriptOnUiThread("bibleView.setToolbarOffset($toolbarOffset, {immediate: true});")
    }

    fun onEvent(event: MainBibleActivity.FullScreenEvent) {
        if(isTopWindow && contentVisible && window.isVisible)
            executeJavascriptOnUiThread("bibleView.setToolbarOffset($toolbarOffset);")
    }

    fun onEvent(event: WebViewsBuiltEvent) {
        checkWindows = true
    }

    val isTopWindow
        get() = !CommonUtils.isSplitVertically || windowControl.windowRepository.firstVisibleWindow == window

    val toolbarOffset
        get() =
            if(isTopWindow && !SharedActivityState.instance.isFullScreen)
                (mainBibleActivity.topOffset2
                    / mainBibleActivity.resources.displayMetrics.density)
            else 0F

    private var separatorMoving = false

    fun onEvent(event: WindowSizeChangedEvent) {
        Log.d(TAG, "window size changed")
        separatorMoving = !event.isFinished
        if(!separatorMoving && !CommonUtils.isSplitVertically) {
            doCheckWindows(true)
        }
    }

    override fun onSizeChanged(w: Int, h: Int, ow: Int, oh: Int) {
        super.onSizeChanged(w, h, ow, oh)
        if(lastUpdated != 0L && !separatorMoving && w != ow) {
            doCheckWindows()
        }
    }

    private fun doCheckWindows(force: Boolean = false) {
        if(checkWindows || force) {
            executeJavascript("bibleView.setToolbarOffset($toolbarOffset, {doNotScroll: true});")
            if (window.pageManager.currentPage.bookCategory == BookCategory.BIBLE) {
                executeJavascript("registerVersePositions()")
                scrollOrJumpToVerse(window.pageManager.currentBible.currentBibleVerse.verse, true)
            }
            checkWindows = false
        }
    }

    override fun onDetachedFromWindow() {
        super.onDetachedFromWindow()
        Log.d(TAG, "Detached from window")
        // prevent random verse changes while layout is being rebuild because of window changes
        bibleJavascriptInterface.notificationsEnabled = false
        pauseTiltScroll()
    }

    fun onEventMainThread(event: AfterRemoveWebViewEvent) {
        if(toBeDestroyed)
            doDestroy()
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        Log.d(TAG, "Attached to window")
        if (windowControl.isActiveWindow(window)) {
            bibleJavascriptInterface.notificationsEnabled = true

            // may have returned from MyNote view
            resumeTiltScroll()
        }
        if(contentVisible) {
            updateTextDisplaySettings()
        }
    }

    /** move the view so the selected verse is at the top or at least visible
     * @param verse
     */
    fun scrollOrJumpToVerseOnUIThread(verse: Verse) {
        val restoreOngoing = window.restoreOngoing
        runOnUiThread {
            scrollOrJumpToVerse(verse, restoreOngoing)
        }
    }

    /** move the view so the selected verse is at the top or at least visible
     */
    private fun scrollOrJumpToVerse(verse: Verse, restoreOngoing: Boolean = false) {
        Log.d(TAG, "Scroll or jump to:$verse")
        val now = if(!contentVisible || restoreOngoing) "true" else "false"
        executeJavascript("bibleView.scrollToVerse('${getIdToJumpTo(verse)}', $now, $toolbarOffset);")
    }

    internal inner class BibleViewLongClickListener(private var defaultValue: Boolean) : View.OnLongClickListener {

        override fun onLongClick(v: View): Boolean {
            Log.d(TAG, "onLongClickListener")
            val result = hitTestResult
            return if (result.type == HitTestResult.SRC_ANCHOR_TYPE) {
                setContextMenuInfo(result.extra!!)
                v.showContextMenu()
            } else {
                contextMenuInfo = null
                defaultValue
            }
        }
    }

    /**
     * if verse 1 then jump to just after chapter divider at top of screen
     */
    private fun getIdToJumpTo(verse: Verse): String {
        return "v-${verse.toV11n(initialVerse!!.versification).ordinal}"
    }

    /**
     * Either enable verse selection or the default text selection
     */
    private fun enableSelection() {
        if (window.pageManager.isBibleShown) {
            // handle long click ourselves and prevent webview showing text selection automatically
            setOnLongClickListener(BibleViewLongClickListener(true))
            isLongClickable = false
        } else {
            // reset handling of long press
            setOnLongClickListener(BibleViewLongClickListener(false))
        }
    }

    private fun setContextMenuInfo(target: String) {
        this.contextMenuInfo = BibleViewContextMenuInfo(this, target)
    }

    override fun getContextMenuInfo(): ContextMenuInfo? {
        return contextMenuInfo
    }

    internal inner class BibleViewContextMenuInfo(targetView: View, private var targetLink: String) : ContextMenuInfo {
        private var targetView: BibleView = targetView as BibleView

        fun activate(itemId: Int) {
            when (itemId) {
                R.id.open_link_in_special_window -> targetView.linkControl.setWindowMode(LinkControl.WINDOW_MODE_SPECIAL)
                R.id.open_link_in_new_window -> targetView.linkControl.setWindowMode(LinkControl.WINDOW_MODE_NEW)
                R.id.open_link_in_this_window -> targetView.linkControl.setWindowMode(LinkControl.WINDOW_MODE_THIS)
            }
            targetView.linkControl.loadApplicationUrl(targetLink)
            targetView.linkControl.setWindowMode(LinkControl.WINDOW_MODE_UNDEFINED)
            contextMenuInfo = null
        }
    }

    override fun enableVerseTouchSelection() {
        gestureListener.setVerseSelectionMode(true)
        executeJavascriptOnUiThread("enableVerseTouchSelection()")
    }

    override fun disableVerseTouchSelection() {
        executeJavascriptOnUiThread("disableVerseTouchSelection()")
        gestureListener.setVerseSelectionMode(false)
    }

    override fun highlightVerse(chapterVerse: ChapterVerse, start: Boolean) {
        val offset = if(isTopWindow) (mainBibleActivity.topOffset2 / mainBibleActivity.resources.displayMetrics.density) else 0f
        executeJavascriptOnUiThread("highlightVerse('" + chapterVerse.toHtmlId() + "' , $start, $offset)")
    }

    override fun unhighlightVerse(chapterVerse: ChapterVerse) {
        executeJavascriptOnUiThread("unhighlightVerse('" + chapterVerse.toHtmlId() + "')")
    }

    override fun clearVerseHighlight() {
        executeJavascriptOnUiThread("clearVerseHighlight()")
    }

    private fun executeJavascriptOnUiThread(javascript: String) {
        runOnUiThread { executeJavascript(javascript) }
    }

    private fun runOnUiThread(runnable: () -> Unit) {
        if(Looper.myLooper() == Looper.getMainLooper()) {
            runnable()
        } else {
            post(runnable)
        }
    }

    //private fun executeJavascript2(javascript: String, callBack: ((rv: String) -> Unit)? = null) {
    //    Log.d(TAG, "Executing JS: $javascript")
    //    evaluateJavascript("$javascript;", callBack)
    //}

    private fun executeJavascript(javascript: String, callBack: ((rv: String) -> Unit)? = null) {
        Log.d(TAG, "Executing JS: $javascript")
        evaluateJavascript("$javascript;", callBack)
        //evaluateJavascript("andbible.$javascript;", callBack)
    }

    fun insertTextAtTop(chapter: Int, osisFragment: String) {
        addChapter(chapter)
        executeJavascriptOnUiThread("bibleView.insertThisTextAtTop({key: $chapter, content: `$osisFragment`});")
    }

    fun insertTextAtEnd(chapter: Int, osisFragment: String) {
        addChapter(chapter)
        executeJavascriptOnUiThread("bibleView.insertThisTextAtEnd({key: $chapter, content: `$osisFragment`});")
    }

    fun setContentReady() {
        synchronized(this) {
            if(needsOsisContent) {
                runOnUiThread {
                    replaceOsis()
                }
            } else {
                htmlLoadingOngoing = false
                contentVisible = true
            }
        }
    }

    fun hasChapterLoaded(chapter: Int) = chapter in minChapter..maxChapter

    fun setClientReady() {
        htmlLoadingOngoing = false;
        replaceOsis()
    }

    var onDestroy: (() -> Unit)? = null

    private val TAG get() = "BibleView[${windowRef.get()?.id}]"

    companion object {
        // never go to 0 because a bug in Android prevents invalidate after loadDataWithBaseURL so
        // no scrollOrJumpToVerse will occur
        private const val TOP_OF_SCREEN = 1
    }
}
