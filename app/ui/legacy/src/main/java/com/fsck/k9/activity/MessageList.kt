package com.fsck.k9.activity

import android.app.SearchManager
import android.content.Context
import android.content.Intent
import android.content.IntentSender
import android.content.res.Configuration
import android.os.Bundle
import android.os.Parcelable
import android.view.KeyEvent
import android.view.Menu
import android.view.MenuItem
import android.view.View
import android.view.animation.AnimationUtils
import android.widget.ProgressBar
import android.widget.Toast
import androidx.appcompat.app.ActionBar
import androidx.appcompat.app.ActionBarDrawerToggle
import androidx.fragment.app.FragmentManager
import androidx.fragment.app.FragmentTransaction
import com.fsck.k9.Account
import com.fsck.k9.Account.SortType
import com.fsck.k9.K9
import com.fsck.k9.K9.SplitViewMode
import com.fsck.k9.Preferences
import com.fsck.k9.account.BackgroundAccountRemover
import com.fsck.k9.activity.compose.MessageActions
import com.fsck.k9.controller.MessageReference
import com.fsck.k9.fragment.MessageListFragment
import com.fsck.k9.fragment.MessageListFragment.MessageListFragmentListener
import com.fsck.k9.helper.Contacts
import com.fsck.k9.helper.ParcelableUtil
import com.fsck.k9.mailstore.SearchStatusManager
import com.fsck.k9.mailstore.StorageManager
import com.fsck.k9.mailstore.StorageManager.StorageListener
import com.fsck.k9.notification.NotificationChannelManager
import com.fsck.k9.search.LocalSearch
import com.fsck.k9.search.SearchAccount
import com.fsck.k9.search.SearchSpecification
import com.fsck.k9.search.SearchSpecification.SearchCondition
import com.fsck.k9.search.SearchSpecification.SearchField
import com.fsck.k9.ui.BuildConfig
import com.fsck.k9.ui.K9Drawer
import com.fsck.k9.ui.R
import com.fsck.k9.ui.base.K9Activity
import com.fsck.k9.ui.base.Theme
import com.fsck.k9.ui.managefolders.ManageFoldersActivity
import com.fsck.k9.ui.messagelist.DefaultFolderProvider
import com.fsck.k9.ui.messageview.MessageViewFragment
import com.fsck.k9.ui.messageview.MessageViewFragment.MessageViewFragmentListener
import com.fsck.k9.ui.messageview.PlaceholderFragment
import com.fsck.k9.ui.onboarding.OnboardingActivity
import com.fsck.k9.ui.permissions.K9PermissionUiHelper
import com.fsck.k9.ui.permissions.Permission
import com.fsck.k9.ui.permissions.PermissionUiHelper
import com.fsck.k9.view.ViewSwitcher
import com.fsck.k9.view.ViewSwitcher.OnSwitchCompleteListener
import com.mikepenz.materialdrawer.Drawer.OnDrawerListener
import org.koin.android.ext.android.inject
import org.koin.core.KoinComponent
import org.koin.core.inject
import timber.log.Timber

/**
 * MessageList is the primary user interface for the program. This Activity shows a list of messages.
 *
 * From this Activity the user can perform all standard message operations.
 */
open class MessageList :
    K9Activity(),
    MessageListFragmentListener,
    MessageViewFragmentListener,
    FragmentManager.OnBackStackChangedListener,
    OnSwitchCompleteListener,
    PermissionUiHelper {

    protected val searchStatusManager: SearchStatusManager by inject()
    private val preferences: Preferences by inject()
    private val channelUtils: NotificationChannelManager by inject()
    private val defaultFolderProvider: DefaultFolderProvider by inject()
    private val accountRemover: BackgroundAccountRemover by inject()

    private val storageListener: StorageListener = StorageListenerImplementation()
    private val permissionUiHelper: PermissionUiHelper = K9PermissionUiHelper(this)

    private var actionBar: ActionBar? = null
    private var drawerToggle: ActionBarDrawerToggle? = null
    private var drawer: K9Drawer? = null
    private var openFolderTransaction: FragmentTransaction? = null
    private var menu: Menu? = null
    private var progressBar: ProgressBar? = null
    private var messageViewPlaceHolder: PlaceholderFragment? = null
    private var messageListFragment: MessageListFragment? = null
    private var messageViewFragment: MessageViewFragment? = null
    private var firstBackStackId = -1
    private var account: Account? = null
    private var search: LocalSearch? = null
    private var singleFolderMode = false
    private var lastDirection = if (K9.isMessageViewShowNext) NEXT else PREVIOUS

    private var messageListActivityAppearance: MessageListActivityAppearance? = null

    /**
     * `true` if the message list should be displayed as flat list (i.e. no threading)
     * regardless whether or not message threading was enabled in the settings. This is used for
     * filtered views, e.g. when only displaying the unread messages in a folder.
     */
    private var noThreading = false
    private var displayMode: DisplayMode? = null
    private var messageReference: MessageReference? = null

    /**
     * `true` when the message list was displayed once. This is used in
     * [.onBackPressed] to decide whether to go from the message view to the message list or
     * finish the activity.
     */
    private var messageListWasDisplayed = false
    private var viewSwitcher: ViewSwitcher? = null

    public override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val accounts = preferences.accounts
        deleteIncompleteAccounts(accounts)
        val hasAccountSetup = accounts.any { it.isFinishedSetup }
        if (!hasAccountSetup) {
            OnboardingActivity.launch(this)
            finish()
            return
        }

        if (UpgradeDatabases.actionUpgradeDatabases(this, intent)) {
            finish()
            return
        }

        if (useSplitView()) {
            setLayout(R.layout.split_message_list)
        } else {
            setLayout(R.layout.message_list)
            viewSwitcher = findViewById<ViewSwitcher>(R.id.container).apply {
                firstInAnimation = AnimationUtils.loadAnimation(this@MessageList, R.anim.slide_in_left)
                firstOutAnimation = AnimationUtils.loadAnimation(this@MessageList, R.anim.slide_out_right)
                secondInAnimation = AnimationUtils.loadAnimation(this@MessageList, R.anim.slide_in_right)
                secondOutAnimation = AnimationUtils.loadAnimation(this@MessageList, R.anim.slide_out_left)
                setOnSwitchCompleteListener(this@MessageList)
            }
        }

        initializeActionBar()
        // 初始化抽屉布局以及数据
        initializeDrawer(savedInstanceState)

        if (!decodeExtras(intent)) {
            return
        }

        if (isDrawerEnabled) {
            drawer!!.updateUserAccountsAndFolders(accounts[0])
        }

        findFragments()
        initializeDisplayMode(savedInstanceState)
        initializeLayout()
        initializeFragments()
        displayViews()
        channelUtils.updateChannels()

        if (savedInstanceState == null) {
            checkAndRequestPermissions()
        }

        openRealAccount(accounts[0])

    }

    public override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)

        if (isFinishing) {
            return
        }

        setIntent(intent)

        if (firstBackStackId >= 0) {
            supportFragmentManager.popBackStackImmediate(firstBackStackId, FragmentManager.POP_BACK_STACK_INCLUSIVE)
            firstBackStackId = -1
        }

        removeMessageListFragment()
        removeMessageViewFragment()

        messageReference = null
        search = null

        if (!decodeExtras(intent)) {
            return
        }

        if (isDrawerEnabled) {
            drawer!!.updateUserAccountsAndFolders(account)
        }

        initializeDisplayMode(null)
        initializeFragments()
        displayViews()
    }

    private fun deleteIncompleteAccounts(accounts: List<Account>) {
        accounts.filter { !it.isFinishedSetup }.forEach {
            accountRemover.removeAccountAsync(it.uuid)
        }
    }

    private fun findFragments() {
        val fragmentManager = supportFragmentManager
        messageListFragment = fragmentManager.findFragmentById(R.id.message_list_container) as MessageListFragment?
        messageViewFragment = fragmentManager.findFragmentByTag(FRAGMENT_TAG_MESSAGE_VIEW) as MessageViewFragment?

        messageListFragment?.let { messageListFragment ->
            initializeFromLocalSearch(messageListFragment.localSearch)
        }
    }

    private fun initializeFragments() {
        val fragmentManager = supportFragmentManager
        fragmentManager.addOnBackStackChangedListener(this)

        val hasMessageListFragment = messageListFragment != null
        if (!hasMessageListFragment) {
            val fragmentTransaction = fragmentManager.beginTransaction()
            val messageListFragment = MessageListFragment.newInstance(
                search!!, false, K9.isThreadedViewEnabled && !noThreading
            )
            fragmentTransaction.add(R.id.message_list_container, messageListFragment)
            fragmentTransaction.commit()

            this.messageListFragment = messageListFragment
        }

        // Check if the fragment wasn't restarted and has a MessageReference in the arguments.
        // If so, open the referenced message.
        if (!hasMessageListFragment && messageViewFragment == null && messageReference != null) {
            openMessage(messageReference!!)
        }
    }

    /**
     * Set the initial display mode (message list, message view, or split view).
     *
     * **Note:**
     * This method has to be called after [.findFragments] because the result depends on
     * the availability of a [MessageViewFragment] instance.
     */
    private fun initializeDisplayMode(savedInstanceState: Bundle?) {
        if (useSplitView()) {
            displayMode = DisplayMode.SPLIT_VIEW
            return
        }

        if (savedInstanceState != null) {
            val savedDisplayMode = savedInstanceState.getSerializable(STATE_DISPLAY_MODE) as DisplayMode?
            if (savedDisplayMode != DisplayMode.SPLIT_VIEW) {
                displayMode = savedDisplayMode
                return
            }
        }

        displayMode = if (messageViewFragment != null || messageReference != null) {
            DisplayMode.MESSAGE_VIEW
        } else {
            DisplayMode.MESSAGE_LIST
        }
    }

    private fun useSplitView(): Boolean {
        val splitViewMode = K9.splitViewMode
        val orientation = resources.configuration.orientation
        return splitViewMode === SplitViewMode.ALWAYS ||
            splitViewMode === SplitViewMode.WHEN_IN_LANDSCAPE && orientation == Configuration.ORIENTATION_LANDSCAPE
    }

    private fun initializeLayout() {
        progressBar = findViewById(R.id.message_list_progress)
        messageViewPlaceHolder = PlaceholderFragment()
    }

    private fun displayViews() {
        when (displayMode) {
            DisplayMode.MESSAGE_LIST -> {
                showMessageList()
            }
            DisplayMode.MESSAGE_VIEW -> {
                showMessageView()
            }
            DisplayMode.SPLIT_VIEW -> {
                messageListWasDisplayed = true
                if (messageViewFragment == null) {
                    showMessageViewPlaceHolder()
                } else {
                    val activeMessage = messageViewFragment!!.messageReference
                    if (activeMessage != null) {
                        messageListFragment!!.setActiveMessage(activeMessage)
                    }
                }
            }
        }
    }

    private fun decodeExtras(intent: Intent): Boolean {
        val action = intent.action
        if (Intent.ACTION_VIEW == action && intent.data != null) {
            val uri = intent.data!!
            val segmentList = uri.pathSegments

            val accountId = segmentList[0]
            val accounts = preferences.availableAccounts
            for (account in accounts) {
                if (account.accountNumber.toString() == accountId) {
                    val folderId = segmentList[1].toLong()
                    val messageUid = segmentList[2]
                    messageReference = MessageReference(account.uuid, folderId, messageUid, null)
                    break
                }
            }
        } else if (ACTION_SHORTCUT == action) {
            // Handle shortcut intents
            val specialFolder = intent.getStringExtra(EXTRA_SPECIAL_FOLDER)
            if (SearchAccount.UNIFIED_INBOX == specialFolder) {
                search = SearchAccount.createUnifiedInboxAccount().relatedSearch
            }
        } else if (intent.getStringExtra(SearchManager.QUERY) != null) {
            // check if this intent comes from the system search ( remote )
            if (Intent.ACTION_SEARCH == intent.action) {
                // Query was received from Search Dialog
                val query = intent.getStringExtra(SearchManager.QUERY).trim()

                search = LocalSearch(getString(R.string.search_results))
                search!!.isManualSearch = true
                noThreading = true

                search!!.or(SearchCondition(SearchField.SENDER, SearchSpecification.Attribute.CONTAINS, query))
                search!!.or(SearchCondition(SearchField.SUBJECT, SearchSpecification.Attribute.CONTAINS, query))
                search!!.or(SearchCondition(SearchField.MESSAGE_CONTENTS, SearchSpecification.Attribute.CONTAINS, query))

                val appData = intent.getBundleExtra(SearchManager.APP_DATA)
                if (appData != null) {
                    val searchAccountUuid = appData.getString(EXTRA_SEARCH_ACCOUNT)
                    if (searchAccountUuid != null) {
                        search!!.addAccountUuid(searchAccountUuid)
                        // searches started from a folder list activity will provide an account, but no folder
                        if (appData.containsKey(EXTRA_SEARCH_FOLDER)) {
                            val folderId = appData.getLong(EXTRA_SEARCH_FOLDER)
                            search!!.addAllowedFolder(folderId)
                        }
                    } else if (BuildConfig.DEBUG) {
                        throw AssertionError("Invalid app data in search intent")
                    }
                }
            }
        } else {
            // regular LocalSearch object was passed
            search = if (intent.hasExtra(EXTRA_SEARCH)) {
                ParcelableUtil.unmarshall(intent.getByteArrayExtra(EXTRA_SEARCH), LocalSearch.CREATOR)
            } else {
                null
            }
            noThreading = intent.getBooleanExtra(EXTRA_NO_THREADING, false)
        }

        if (messageReference == null) {
            val messageReferenceString = intent.getStringExtra(EXTRA_MESSAGE_REFERENCE)
            messageReference = MessageReference.parse(messageReferenceString)
        }

        if (messageReference != null) {
            search = LocalSearch()
            search!!.addAccountUuid(messageReference!!.accountUuid)
            val folderId = messageReference!!.folderId
            search!!.addAllowedFolder(folderId)
        }

        if (search == null) {
            val accountUuid = intent.getStringExtra("account")
            if (accountUuid != null) {
                // We've most likely been started by an old unread widget or accounts shortcut
                val folderServerId = intent.getStringExtra("folder")
                val folderId: Long
                if (folderServerId == null) {
                    account = preferences.getAccount(accountUuid)
                    folderId = defaultFolderProvider.getDefaultFolder(account!!)
                } else {
                    // FIXME: load folder ID for folderServerId
                    folderId = 0
                }
                search = LocalSearch()
                search!!.addAccountUuid(accountUuid)
                search!!.addAllowedFolder(folderId)
            } else {
                if (K9.isHideSpecialAccounts) {
                    account = preferences.defaultAccount
                    search = LocalSearch()
                    search!!.addAccountUuid(account!!.uuid)
                    val folderId = defaultFolderProvider.getDefaultFolder(account!!)
                    search!!.addAllowedFolder(folderId)
                } else {
                    account = null
                    search = SearchAccount.createUnifiedInboxAccount().relatedSearch
                }
            }
        }

        initializeFromLocalSearch(search)

        if (account != null && !account!!.isAvailable(this)) {
            onAccountUnavailable()
            return false
        }

        return true
    }

    private fun checkAndRequestPermissions() {
        if (!hasPermission(Permission.READ_CONTACTS)) {
            requestPermissionOrShowRationale(Permission.READ_CONTACTS)
        }
    }

    public override fun onPause() {
        super.onPause()
        StorageManager.getInstance(application).removeListener(storageListener)
    }

    public override fun onResume() {
        super.onResume()

        if (messageListActivityAppearance == null) {
            messageListActivityAppearance = MessageListActivityAppearance.create()
        } else if (messageListActivityAppearance != MessageListActivityAppearance.create()) {
            recreate()
        }

        if (this !is Search) {
            // necessary b/c no guarantee Search.onStop will be called before MessageList.onResume
            // when returning from search results
            searchStatusManager.isActive = false
        }

        if (account != null && !account!!.isAvailable(this)) {
            onAccountUnavailable()
            return
        }

        StorageManager.getInstance(application).addListener(storageListener)
    }

    override fun onStart() {
        super.onStart()
        Contacts.clearCache()
    }

    public override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)

        outState.putSerializable(STATE_DISPLAY_MODE, displayMode)
        outState.putBoolean(STATE_MESSAGE_LIST_WAS_DISPLAYED, messageListWasDisplayed)
        outState.putInt(STATE_FIRST_BACK_STACK_ID, firstBackStackId)
    }

    public override fun onRestoreInstanceState(savedInstanceState: Bundle) {
        super.onRestoreInstanceState(savedInstanceState)

        messageListWasDisplayed = savedInstanceState.getBoolean(STATE_MESSAGE_LIST_WAS_DISPLAYED)
        firstBackStackId = savedInstanceState.getInt(STATE_FIRST_BACK_STACK_ID)
    }

    private fun initializeActionBar() {
        actionBar = supportActionBar
        actionBar!!.setDisplayHomeAsUpEnabled(true)
    }

    private fun initializeDrawer(savedInstanceState: Bundle?) {
        if (!isDrawerEnabled) {
            return
        }

        drawer = K9Drawer(this, savedInstanceState)

        val drawerLayout = drawer!!.layout
        drawerToggle = ActionBarDrawerToggle(
            this, drawerLayout, null,
            R.string.navigation_drawer_open, R.string.navigation_drawer_close
        )
        drawerLayout.addDrawerListener(drawerToggle!!)
        drawerToggle!!.syncState()
    }

    fun createOnDrawerListener(): OnDrawerListener {
        return object : OnDrawerListener {
            override fun onDrawerClosed(drawerView: View) {
                if (openFolderTransaction != null) {
                    openFolderTransaction!!.commit()
                    openFolderTransaction = null
                }
            }

            override fun onDrawerOpened(drawerView: View) = Unit

            override fun onDrawerSlide(drawerView: View, slideOffset: Float) = Unit
        }
    }

    fun openFolder(folderId: Long) {
        val search = LocalSearch()
        search.addAccountUuid(account!!.uuid)
        search.addAllowedFolder(folderId)

        performSearch(search)
    }

    private fun openFolderImmediately(folderId: Long) {
        openFolder(folderId)
        openFolderTransaction!!.commit()
        openFolderTransaction = null
    }

    fun openUnifiedInbox() {
        account = null
        drawer!!.selectUnifiedInbox()
        actionDisplaySearch(this, SearchAccount.createUnifiedInboxAccount().relatedSearch, false, false)
    }

    fun launchManageFoldersScreen() {
        if (account == null) {
            Timber.e("Tried to open \"Manage folders\", but no account selected!")
            return
        }

        ManageFoldersActivity.launch(this, account!!)
    }

    fun openRealAccount(account: Account) {
        val folderId = defaultFolderProvider.getDefaultFolder(account)

        val search = LocalSearch()
        search.addAllowedFolder(folderId)
        search.addAccountUuid(account.uuid)
        actionDisplaySearch(this, search, noThreading = false, newTask = false)
    }

    private fun performSearch(search: LocalSearch) {
        initializeFromLocalSearch(search)

        val fragmentManager = supportFragmentManager

        check(!(BuildConfig.DEBUG && fragmentManager.backStackEntryCount > 0)) {
            "Don't call performSearch() while there are fragments on the back stack"
        }

        val openFolderTransaction = fragmentManager.beginTransaction()
        val messageListFragment = MessageListFragment.newInstance(search, false, K9.isThreadedViewEnabled)
        openFolderTransaction.replace(R.id.message_list_container, messageListFragment)

        this.messageListFragment = messageListFragment
        this.openFolderTransaction = openFolderTransaction
    }

    protected open val isDrawerEnabled: Boolean = true

    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        var eventHandled = false
        if (KeyEvent.ACTION_DOWN == event.action) {
            eventHandled = onCustomKeyDown(event.keyCode, event)
        }

        if (!eventHandled) {
            eventHandled = super.dispatchKeyEvent(event)
        }

        return eventHandled
    }

    override fun onBackPressed() {
        if (isDrawerEnabled && drawer!!.isOpen) {
            drawer!!.close()
        } else if (displayMode == DisplayMode.MESSAGE_VIEW && messageListWasDisplayed) {
            showMessageList()
        } else {
            if (isDrawerEnabled && account != null && supportFragmentManager.backStackEntryCount == 0) {
                val defaultFolderId = defaultFolderProvider.getDefaultFolder(account!!)
                val currentFolder = if (singleFolderMode) search!!.folderIds[0] else null
                if (currentFolder == null || defaultFolderId != currentFolder) {
                    openFolderImmediately(defaultFolderId)
                } else {
                    super.onBackPressed()
                }
            } else {
                super.onBackPressed()
            }
        }
    }

    /**
     * Handle hotkeys
     *
     * This method is called by [.dispatchKeyEvent] before any view had the chance to consume this key event.
     *
     * @return `true` if this event was consumed.
     */
    private fun onCustomKeyDown(keyCode: Int, event: KeyEvent): Boolean {
        if (!event.hasNoModifiers()) return false

        when (keyCode) {
            KeyEvent.KEYCODE_VOLUME_UP -> {
                if (messageViewFragment != null && displayMode != DisplayMode.MESSAGE_LIST &&
                    K9.isUseVolumeKeysForNavigation
                ) {
                    showPreviousMessage()
                    return true
                } else if (displayMode != DisplayMode.MESSAGE_VIEW && K9.isUseVolumeKeysForListNavigation) {
                    messageListFragment!!.onMoveUp()
                    return true
                }
            }
            KeyEvent.KEYCODE_VOLUME_DOWN -> {
                if (messageViewFragment != null && displayMode != DisplayMode.MESSAGE_LIST &&
                    K9.isUseVolumeKeysForNavigation
                ) {
                    showNextMessage()
                    return true
                } else if (displayMode != DisplayMode.MESSAGE_VIEW && K9.isUseVolumeKeysForListNavigation) {
                    messageListFragment!!.onMoveDown()
                    return true
                }
            }
            KeyEvent.KEYCODE_C -> {
                messageListFragment!!.onCompose()
                return true
            }
            KeyEvent.KEYCODE_O -> {
                messageListFragment!!.onCycleSort()
                return true
            }
            KeyEvent.KEYCODE_I -> {
                messageListFragment!!.onReverseSort()
                return true
            }
            KeyEvent.KEYCODE_DEL, KeyEvent.KEYCODE_D -> {
                if (displayMode == DisplayMode.MESSAGE_LIST) {
                    messageListFragment!!.onDelete()
                } else if (messageViewFragment != null) {
                    messageViewFragment!!.onDelete()
                }
                return true
            }
            KeyEvent.KEYCODE_S -> {
                messageListFragment!!.toggleMessageSelect()
                return true
            }
            KeyEvent.KEYCODE_G -> {
                if (displayMode == DisplayMode.MESSAGE_LIST) {
                    messageListFragment!!.onToggleFlagged()
                } else if (messageViewFragment != null) {
                    messageViewFragment!!.onToggleFlagged()
                }
                return true
            }
            KeyEvent.KEYCODE_M -> {
                if (displayMode == DisplayMode.MESSAGE_LIST) {
                    messageListFragment!!.onMove()
                } else if (messageViewFragment != null) {
                    messageViewFragment!!.onMove()
                }
                return true
            }
            KeyEvent.KEYCODE_V -> {
                if (displayMode == DisplayMode.MESSAGE_LIST) {
                    messageListFragment!!.onArchive()
                } else if (messageViewFragment != null) {
                    messageViewFragment!!.onArchive()
                }
                return true
            }
            KeyEvent.KEYCODE_Y -> {
                if (displayMode == DisplayMode.MESSAGE_LIST) {
                    messageListFragment!!.onCopy()
                } else if (messageViewFragment != null) {
                    messageViewFragment!!.onCopy()
                }
                return true
            }
            KeyEvent.KEYCODE_Z -> {
                if (displayMode == DisplayMode.MESSAGE_LIST) {
                    messageListFragment!!.onToggleRead()
                } else if (messageViewFragment != null) {
                    messageViewFragment!!.onToggleRead()
                }
                return true
            }
            KeyEvent.KEYCODE_F -> {
                if (messageViewFragment != null) {
                    messageViewFragment!!.onForward()
                }
                return true
            }
            KeyEvent.KEYCODE_A -> {
                if (messageViewFragment != null) {
                    messageViewFragment!!.onReplyAll()
                }
                return true
            }
            KeyEvent.KEYCODE_R -> {
                if (messageViewFragment != null) {
                    messageViewFragment!!.onReply()
                }
                return true
            }
            KeyEvent.KEYCODE_J, KeyEvent.KEYCODE_P -> {
                if (messageViewFragment != null) {
                    showPreviousMessage()
                }
                return true
            }
            KeyEvent.KEYCODE_N, KeyEvent.KEYCODE_K -> {
                if (messageViewFragment != null) {
                    showNextMessage()
                }
                return true
            }
            KeyEvent.KEYCODE_H -> {
                val toast = if (displayMode == DisplayMode.MESSAGE_LIST) {
                    Toast.makeText(this, R.string.message_list_help_key, Toast.LENGTH_LONG)
                } else {
                    Toast.makeText(this, R.string.message_view_help_key, Toast.LENGTH_LONG)
                }
                toast.show()
                return true
            }
            KeyEvent.KEYCODE_DPAD_LEFT -> {
                return if (messageViewFragment != null && displayMode == DisplayMode.MESSAGE_VIEW) {
                    showPreviousMessage()
                } else {
                    false
                }
            }
            KeyEvent.KEYCODE_DPAD_RIGHT -> {
                return if (messageViewFragment != null && displayMode == DisplayMode.MESSAGE_VIEW) {
                    showNextMessage()
                } else {
                    false
                }
            }
        }

        return false
    }

    override fun onKeyUp(keyCode: Int, event: KeyEvent): Boolean {
        // Swallow these events too to avoid the audible notification of a volume change
        if (K9.isUseVolumeKeysForListNavigation) {
            if (keyCode == KeyEvent.KEYCODE_VOLUME_UP || keyCode == KeyEvent.KEYCODE_VOLUME_DOWN) {
                Timber.v("Swallowed key up.")
                return true
            }
        }

        return super.onKeyUp(keyCode, event)
    }

    override fun onSearchRequested(): Boolean {
        return messageListFragment!!.onSearchRequested()
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean {
        val id = item.itemId
        if (id == android.R.id.home) {
            if (displayMode != DisplayMode.MESSAGE_VIEW && !isAdditionalMessageListDisplayed) {
                if (isDrawerEnabled) {
                    if (drawer!!.isOpen) {
                        drawer!!.close()
                    } else {
                        drawer!!.open()
                    }
                } else {
                    finish()
                }
            } else {
                goBack()
            }
            return true
        } else if (id == R.id.compose) {
            messageListFragment!!.onCompose()
            return true
        } else if (id == R.id.toggle_message_view_theme) {
            onToggleTheme()
            return true
        } else if (id == R.id.set_sort_date) { // MessageList
            messageListFragment!!.changeSort(SortType.SORT_DATE)
            return true
        } else if (id == R.id.set_sort_arrival) {
            messageListFragment!!.changeSort(SortType.SORT_ARRIVAL)
            return true
        } else if (id == R.id.set_sort_subject) {
            messageListFragment!!.changeSort(SortType.SORT_SUBJECT)
            return true
        } else if (id == R.id.set_sort_sender) {
            messageListFragment!!.changeSort(SortType.SORT_SENDER)
            return true
        } else if (id == R.id.set_sort_flag) {
            messageListFragment!!.changeSort(SortType.SORT_FLAGGED)
            return true
        } else if (id == R.id.set_sort_unread) {
            messageListFragment!!.changeSort(SortType.SORT_UNREAD)
            return true
        } else if (id == R.id.set_sort_attach) {
            messageListFragment!!.changeSort(SortType.SORT_ATTACHMENT)
            return true
        } else if (id == R.id.select_all) {
            messageListFragment!!.selectAll()
            return true
        } else if (id == R.id.search) {
            messageListFragment!!.onSearchRequested()
            return true
        } else if (id == R.id.search_remote) {
            messageListFragment!!.onRemoteSearch()
            return true
        } else if (id == R.id.mark_all_as_read) {
            messageListFragment!!.confirmMarkAllAsRead()
            return true
        } else if (id == R.id.next_message) { // MessageView
            showNextMessage()
            return true
        } else if (id == R.id.previous_message) {
            showPreviousMessage()
            return true
        } else if (id == R.id.delete) {
            messageViewFragment!!.onDelete()
            return true
        } else if (id == R.id.reply) {
            messageViewFragment!!.onReply()
            return true
        } else if (id == R.id.reply_all) {
            messageViewFragment!!.onReplyAll()
            return true
        } else if (id == R.id.forward) {
            messageViewFragment!!.onForward()
            return true
        } else if (id == R.id.forward_as_attachment) {
            messageViewFragment!!.onForwardAsAttachment()
            return true
        } else if (id == R.id.edit_as_new_message) {
            messageViewFragment!!.onEditAsNewMessage()
            return true
        } else if (id == R.id.share) {
            messageViewFragment!!.onSendAlternate()
            return true
        } else if (id == R.id.toggle_unread) {
            messageViewFragment!!.onToggleRead()
            return true
        } else if (id == R.id.archive || id == R.id.refile_archive) {
            messageViewFragment!!.onArchive()
            return true
        } else if (id == R.id.spam || id == R.id.refile_spam) {
            messageViewFragment!!.onSpam()
            return true
        } else if (id == R.id.move || id == R.id.refile_move) {
            messageViewFragment!!.onMove()
            return true
        } else if (id == R.id.copy || id == R.id.refile_copy) {
            messageViewFragment!!.onCopy()
            return true
        } else if (id == R.id.move_to_drafts) {
            messageViewFragment!!.onMoveToDrafts()
            return true
        } else if (id == R.id.show_headers || id == R.id.hide_headers) {
            messageViewFragment!!.onToggleAllHeadersView()
            updateMenu()
            return true
        }

        if (!singleFolderMode) {
            // None of the options after this point are "safe" for search results
            // TODO: This is not true for "unread" and "starred" searches in regular folders
            return false
        }

        return when (id) {
            R.id.send_messages -> {
                messageListFragment!!.onSendPendingMessages()
                true
            }
            R.id.expunge -> {
                messageListFragment!!.onExpunge()
                true
            }
            R.id.empty_trash -> {
                messageListFragment!!.onEmptyTrash()
                true
            }
            else -> {
                super.onOptionsItemSelected(item)
            }
        }
    }

    override fun onCreateOptionsMenu(menu: Menu): Boolean {
        menuInflater.inflate(R.menu.message_list_option, menu)
        this.menu = menu
        return true
    }

    override fun onPrepareOptionsMenu(menu: Menu): Boolean {
        super.onPrepareOptionsMenu(menu)
        configureMenu(menu)
        return true
    }

    /**
     * Hide menu items not appropriate for the current context.
     *
     * **Note:**
     * Please adjust the comments in `res/menu/message_list_option.xml` if you change the  visibility of a menu item
     * in this method.
     */
    private fun configureMenu(menu: Menu?) {
        if (menu == null) return

        // Set visibility of menu items related to the message view
        if (displayMode == DisplayMode.MESSAGE_LIST || messageViewFragment == null ||
            !messageViewFragment!!.isInitialized
        ) {
            menu.findItem(R.id.next_message).isVisible = false
            menu.findItem(R.id.previous_message).isVisible = false
            menu.findItem(R.id.single_message_options).isVisible = false
            menu.findItem(R.id.delete).isVisible = false
            menu.findItem(R.id.compose).isVisible = false
            menu.findItem(R.id.archive).isVisible = false
            menu.findItem(R.id.move).isVisible = false
            menu.findItem(R.id.copy).isVisible = false
            menu.findItem(R.id.spam).isVisible = false
            menu.findItem(R.id.refile).isVisible = false
            menu.findItem(R.id.toggle_unread).isVisible = false
            menu.findItem(R.id.toggle_message_view_theme).isVisible = false
            menu.findItem(R.id.show_headers).isVisible = false
            menu.findItem(R.id.hide_headers).isVisible = false
        } else {
            // hide prev/next buttons in split mode
            if (displayMode != DisplayMode.MESSAGE_VIEW) {
                menu.findItem(R.id.next_message).isVisible = false
                menu.findItem(R.id.previous_message).isVisible = false
            } else {
                val ref = messageViewFragment!!.messageReference
                val initialized = messageListFragment != null &&
                    messageListFragment!!.isLoadFinished
                val canDoPrev = initialized && !messageListFragment!!.isFirst(ref)
                val canDoNext = initialized && !messageListFragment!!.isLast(ref)
                val prev = menu.findItem(R.id.previous_message)
                prev.isEnabled = canDoPrev
                prev.icon.alpha = if (canDoPrev) 255 else 127
                val next = menu.findItem(R.id.next_message)
                next.isEnabled = canDoNext
                next.icon.alpha = if (canDoNext) 255 else 127
            }

            val toggleTheme = menu.findItem(R.id.toggle_message_view_theme)
            if (K9.isFixedMessageViewTheme) {
                toggleTheme.isVisible = false
            } else {
                // Set title of menu item to switch to dark/light theme
                if (themeManager.messageViewTheme === Theme.DARK) {
                    toggleTheme.setTitle(R.string.message_view_theme_action_light)
                } else {
                    toggleTheme.setTitle(R.string.message_view_theme_action_dark)
                }
                toggleTheme.isVisible = true
            }

            if (messageViewFragment!!.isOutbox) {
                menu.findItem(R.id.toggle_unread).isVisible = false
            } else {
                // Set title of menu item to toggle the read state of the currently displayed message
                val drawableAttr = if (messageViewFragment!!.isMessageRead) {
                    menu.findItem(R.id.toggle_unread).setTitle(R.string.mark_as_unread_action)
                    intArrayOf(R.attr.iconActionMarkAsUnread)
                } else {
                    menu.findItem(R.id.toggle_unread).setTitle(R.string.mark_as_read_action)
                    intArrayOf(R.attr.iconActionMarkAsRead)
                }
                val typedArray = obtainStyledAttributes(drawableAttr)
                menu.findItem(R.id.toggle_unread).icon = typedArray.getDrawable(0)
                typedArray.recycle()
            }

            menu.findItem(R.id.delete).isVisible = K9.isMessageViewDeleteActionVisible

            // Set visibility of copy, move, archive, spam in action bar and refile submenu
            if (messageViewFragment!!.isCopyCapable) {
                menu.findItem(R.id.copy).isVisible = K9.isMessageViewCopyActionVisible
                menu.findItem(R.id.refile_copy).isVisible = true
            } else {
                menu.findItem(R.id.copy).isVisible = false
                menu.findItem(R.id.refile_copy).isVisible = false
            }

            if (messageViewFragment!!.isMoveCapable) {
                val canMessageBeArchived = messageViewFragment!!.canMessageBeArchived()
                val canMessageBeMovedToSpam = messageViewFragment!!.canMessageBeMovedToSpam()

                menu.findItem(R.id.move).isVisible = K9.isMessageViewMoveActionVisible
                menu.findItem(R.id.archive).isVisible = canMessageBeArchived && K9.isMessageViewArchiveActionVisible
                menu.findItem(R.id.spam).isVisible = canMessageBeMovedToSpam && K9.isMessageViewSpamActionVisible

                menu.findItem(R.id.refile_move).isVisible = true
                menu.findItem(R.id.refile_archive).isVisible = canMessageBeArchived
                menu.findItem(R.id.refile_spam).isVisible = canMessageBeMovedToSpam
            } else {
                menu.findItem(R.id.move).isVisible = false
                menu.findItem(R.id.archive).isVisible = false
                menu.findItem(R.id.spam).isVisible = false

                menu.findItem(R.id.refile).isVisible = false
            }

            if (messageViewFragment!!.isOutbox) {
                menu.findItem(R.id.move_to_drafts).isVisible = true
            }

            if (messageViewFragment!!.allHeadersVisible()) {
                menu.findItem(R.id.show_headers).isVisible = false
            } else {
                menu.findItem(R.id.hide_headers).isVisible = false
            }
        }

        // Set visibility of menu items related to the message list

        // Hide both search menu items by default and enable one when appropriate
        menu.findItem(R.id.search).isVisible = false
        menu.findItem(R.id.search_remote).isVisible = false

        if (displayMode == DisplayMode.MESSAGE_VIEW || messageListFragment == null ||
            !messageListFragment!!.isInitialized
        ) {
            menu.findItem(R.id.set_sort).isVisible = false
            menu.findItem(R.id.select_all).isVisible = false
            menu.findItem(R.id.send_messages).isVisible = false
            menu.findItem(R.id.expunge).isVisible = false
            menu.findItem(R.id.empty_trash).isVisible = false
            menu.findItem(R.id.mark_all_as_read).isVisible = false
        } else {
            menu.findItem(R.id.set_sort).isVisible = true
            menu.findItem(R.id.select_all).isVisible = true
            menu.findItem(R.id.compose).isVisible = true
            menu.findItem(R.id.mark_all_as_read).isVisible = messageListFragment!!.isMarkAllAsReadSupported

            if (!messageListFragment!!.isSingleAccountMode) {
                menu.findItem(R.id.expunge).isVisible = false
                menu.findItem(R.id.send_messages).isVisible = false
            } else {
                menu.findItem(R.id.send_messages).isVisible = messageListFragment!!.isOutbox
                menu.findItem(R.id.expunge).isVisible = messageListFragment!!.isRemoteFolder &&
                    messageListFragment!!.shouldShowExpungeAction()
            }
            menu.findItem(R.id.empty_trash).isVisible = messageListFragment!!.isShowingTrashFolder

            // If this is an explicit local search, show the option to search on the server
            if (!messageListFragment!!.isRemoteSearch && messageListFragment!!.isRemoteSearchAllowed) {
                menu.findItem(R.id.search_remote).isVisible = true
            } else if (!messageListFragment!!.isManualSearch) {
                menu.findItem(R.id.search).isVisible = true
            }
        }
    }

    protected fun onAccountUnavailable() {
        // TODO: Find better way to handle this case.
        Timber.i("Account is unavailable right now: $account")
        finish()
    }

    fun setActionBarTitle(title: String) {
        actionBar!!.title = title
    }

    override fun setMessageListTitle(title: String) {
        if (displayMode != DisplayMode.MESSAGE_VIEW) {
            setActionBarTitle(title)
        }
    }

    override fun setMessageListProgressEnabled(enable: Boolean) {
        progressBar!!.visibility = if (enable) View.VISIBLE else View.INVISIBLE
    }

    override fun setMessageListProgress(progress: Int) {
        progressBar!!.progress = progress
    }

    override fun openMessage(messageReference: MessageReference) {
        val account = preferences.getAccount(messageReference.accountUuid)
        val folderId = messageReference.folderId

        val draftsFolderId = account.draftsFolderId
        if (draftsFolderId != null && folderId == draftsFolderId) {
            MessageActions.actionEditDraft(this, messageReference)
        } else {
            if (messageListFragment != null) {
                messageListFragment!!.setActiveMessage(messageReference)
            }

            val fragment = MessageViewFragment.newInstance(messageReference)
            val fragmentTransaction = supportFragmentManager.beginTransaction()
            fragmentTransaction.replace(R.id.message_view_container, fragment, FRAGMENT_TAG_MESSAGE_VIEW)
            fragmentTransaction.commit()
            messageViewFragment = fragment

            if (displayMode != DisplayMode.SPLIT_VIEW) {
                showMessageView()
            }
        }
    }

    override fun onForward(messageReference: MessageReference, decryptionResultForReply: Parcelable?) {
        MessageActions.actionForward(this, messageReference, decryptionResultForReply)
    }

    override fun onForwardAsAttachment(messageReference: MessageReference, decryptionResultForReply: Parcelable?) {
        MessageActions.actionForwardAsAttachment(this, messageReference, decryptionResultForReply)
    }

    override fun onEditAsNewMessage(messageReference: MessageReference) {
        MessageActions.actionEditDraft(this, messageReference)
    }

    override fun onReply(messageReference: MessageReference, decryptionResultForReply: Parcelable?) {
        MessageActions.actionReply(this, messageReference, false, decryptionResultForReply)
    }

    override fun onReplyAll(messageReference: MessageReference, decryptionResultForReply: Parcelable?) {
        MessageActions.actionReply(this, messageReference, true, decryptionResultForReply)
    }

    override fun onCompose(account: Account?) {
        MessageActions.actionCompose(this, account)
    }

    override fun onBackStackChanged() {
        findFragments()
        if (isDrawerEnabled && !isAdditionalMessageListDisplayed) {
            unlockDrawer()
        }

        if (displayMode == DisplayMode.SPLIT_VIEW) {
            showMessageViewPlaceHolder()
        }

        configureMenu(menu)
    }

    private fun addMessageListFragment(fragment: MessageListFragment, addToBackStack: Boolean) {
        val fragmentTransaction = supportFragmentManager.beginTransaction()

        fragmentTransaction.replace(R.id.message_list_container, fragment)
        if (addToBackStack) {
            fragmentTransaction.addToBackStack(null)
        }

        messageListFragment = fragment

        if (isDrawerEnabled) {
            lockDrawer()
        }

        val transactionId = fragmentTransaction.commit()
        if (transactionId >= 0 && firstBackStackId < 0) {
            firstBackStackId = transactionId
        }
    }

    override fun startSearch(account: Account?, folderId: Long?): Boolean {
        // If this search was started from a MessageList of a single folder, pass along that folder info
        // so that we can enable remote search.
        if (account != null && folderId != null) {
            val appData = Bundle().apply {
                putString(EXTRA_SEARCH_ACCOUNT, account.uuid)
                putLong(EXTRA_SEARCH_FOLDER, folderId)
            }
            startSearch(null, false, appData, false)
        } else {
            // TODO Handle the case where we're searching from within a search result.
            startSearch(null, false, null, false)
        }

        return true
    }

    override fun showThread(account: Account, threadRootId: Long) {
        showMessageViewPlaceHolder()

        val tmpSearch = LocalSearch().apply {
            addAccountUuid(account.uuid)
            and(SearchField.THREAD_ID, threadRootId.toString(), SearchSpecification.Attribute.EQUALS)
        }

        initializeFromLocalSearch(tmpSearch)

        val fragment = MessageListFragment.newInstance(tmpSearch, true, false)
        addMessageListFragment(fragment, true)
    }

    private fun showMessageViewPlaceHolder() {
        removeMessageViewFragment()

        // Add placeholder fragment if necessary
        val fragmentManager = supportFragmentManager
        if (fragmentManager.findFragmentByTag(FRAGMENT_TAG_PLACEHOLDER) == null) {
            val fragmentTransaction = fragmentManager.beginTransaction()
            fragmentTransaction.replace(R.id.message_view_container, messageViewPlaceHolder!!, FRAGMENT_TAG_PLACEHOLDER)
            fragmentTransaction.commit()
        }

        messageListFragment!!.setActiveMessage(null)
    }

    private fun removeMessageViewFragment() {
        if (messageViewFragment != null) {
            val fragmentTransaction = supportFragmentManager.beginTransaction()
            fragmentTransaction.remove(messageViewFragment!!)
            messageViewFragment = null
            fragmentTransaction.commit()

            showDefaultTitleView()
        }
    }

    private fun removeMessageListFragment() {
        val fragmentTransaction = supportFragmentManager.beginTransaction()
        fragmentTransaction.remove(messageListFragment!!)
        messageListFragment = null
        fragmentTransaction.commit()
    }

    override fun remoteSearchStarted() {
        // Remove action button for remote search
        configureMenu(menu)
    }

    override fun goBack() {
        val fragmentManager = supportFragmentManager
        when {
            displayMode == DisplayMode.MESSAGE_VIEW -> showMessageList()
            fragmentManager.backStackEntryCount > 0 -> fragmentManager.popBackStack()
            else -> finish()
        }
    }

    override fun showNextMessageOrReturn() {
        if (K9.isMessageViewReturnToList || !showLogicalNextMessage()) {
            if (displayMode == DisplayMode.SPLIT_VIEW) {
                showMessageViewPlaceHolder()
            } else {
                showMessageList()
            }
        }
    }

    private fun showLogicalNextMessage(): Boolean {
        var result = false
        if (lastDirection == NEXT) {
            result = showNextMessage()
        } else if (lastDirection == PREVIOUS) {
            result = showPreviousMessage()
        }

        if (!result) {
            result = showNextMessage() || showPreviousMessage()
        }

        return result
    }

    override fun setProgress(enable: Boolean) {
        setProgressBarIndeterminateVisibility(enable)
    }

    private fun showNextMessage(): Boolean {
        val ref = messageViewFragment!!.messageReference
        if (ref != null) {
            if (messageListFragment!!.openNext(ref)) {
                lastDirection = NEXT
                return true
            }
        }
        return false
    }

    private fun showPreviousMessage(): Boolean {
        val ref = messageViewFragment!!.messageReference
        if (ref != null) {
            if (messageListFragment!!.openPrevious(ref)) {
                lastDirection = PREVIOUS
                return true
            }
        }
        return false
    }

    private fun showMessageList() {
        messageListWasDisplayed = true
        displayMode = DisplayMode.MESSAGE_LIST
        viewSwitcher!!.showFirstView()

        messageListFragment!!.setActiveMessage(null)

        if (isDrawerEnabled) {
            if (isAdditionalMessageListDisplayed) {
                lockDrawer()
            } else {
                unlockDrawer()
            }
        }

        showDefaultTitleView()
        configureMenu(menu)
    }

    private fun showMessageView() {
        displayMode = DisplayMode.MESSAGE_VIEW

        if (!messageListWasDisplayed) {
            viewSwitcher!!.animateFirstView = false
        }
        viewSwitcher!!.showSecondView()

        if (isDrawerEnabled) {
            lockDrawer()
        }

        showMessageTitleView()
        configureMenu(menu)
    }

    override fun updateMenu() {
        invalidateOptionsMenu()
    }

    override fun disableDeleteAction() {
        menu!!.findItem(R.id.delete).isEnabled = false
    }

    private fun onToggleTheme() {
        themeManager.toggleMessageViewTheme()
        recreate()
    }

    private fun showDefaultTitleView() {
        if (messageListFragment != null) {
            messageListFragment!!.updateTitle()
        }
    }

    private fun showMessageTitleView() {
        setActionBarTitle("")
    }

    override fun onSwitchComplete(displayedChild: Int) {
        if (displayedChild == 0) {
            removeMessageViewFragment()
        }
    }

    override fun startIntentSenderForResult(
        intent: IntentSender,
        requestCode: Int,
        fillInIntent: Intent?,
        flagsMask: Int,
        flagsValues: Int,
        extraFlags: Int
    ) {
        val modifiedRequestCode = requestCode or REQUEST_MASK_PENDING_INTENT
        super.startIntentSenderForResult(intent, modifiedRequestCode, fillInIntent, flagsMask, flagsValues, extraFlags)
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)

        if (requestCode and REQUEST_MASK_PENDING_INTENT == REQUEST_MASK_PENDING_INTENT) {
            val originalRequestCode = requestCode xor REQUEST_MASK_PENDING_INTENT
            if (messageViewFragment != null) {
                messageViewFragment!!.onPendingIntentResult(originalRequestCode, resultCode, data)
            }
        }
    }

    private val isAdditionalMessageListDisplayed: Boolean
        get() = supportFragmentManager.backStackEntryCount > 0

    private fun lockDrawer() {
        drawer!!.lock()
        drawerToggle!!.isDrawerIndicatorEnabled = false
    }

    private fun unlockDrawer() {
        drawer!!.unlock()
        drawerToggle!!.isDrawerIndicatorEnabled = true
    }

    private fun initializeFromLocalSearch(search: LocalSearch?) {
        this.search = search
        singleFolderMode = false

        if (search!!.searchAllAccounts()) {
            account = null
        } else {
            val accountUuids = search.accountUuids
            if (accountUuids.size == 1) {
                account = preferences.getAccount(accountUuids[0])
                val folderIds = search.folderIds
                singleFolderMode = folderIds.size == 1
            } else {
                account = null
            }
        }

        configureDrawer()
    }

    private fun configureDrawer() {
        val drawer = drawer ?: return
        when {
            singleFolderMode -> drawer.selectFolder(search!!.folderIds[0])
            search!!.id == SearchAccount.UNIFIED_INBOX -> drawer.selectUnifiedInbox()
            else -> drawer.deselect()
        }
    }

    override fun hasPermission(permission: Permission): Boolean {
        return permissionUiHelper.hasPermission(permission)
    }

    override fun requestPermissionOrShowRationale(permission: Permission) {
        permissionUiHelper.requestPermissionOrShowRationale(permission)
    }

    override fun requestPermission(permission: Permission) {
        permissionUiHelper.requestPermission(permission)
    }

    private inner class StorageListenerImplementation : StorageListener {
        override fun onUnmount(providerId: String) {
            if (account?.localStorageProviderId == providerId) {
                runOnUiThread { onAccountUnavailable() }
            }
        }

        override fun onMount(providerId: String) = Unit
    }

    private enum class DisplayMode {
        MESSAGE_LIST, MESSAGE_VIEW, SPLIT_VIEW
    }

    companion object : KoinComponent {
        private const val EXTRA_SEARCH = "search_bytes"
        private const val EXTRA_NO_THREADING = "no_threading"

        private const val ACTION_SHORTCUT = "shortcut"
        private const val EXTRA_SPECIAL_FOLDER = "special_folder"

        private const val EXTRA_MESSAGE_REFERENCE = "message_reference"

        // used for remote search
        const val EXTRA_SEARCH_ACCOUNT = "com.fsck.k9.search_account"
        private const val EXTRA_SEARCH_FOLDER = "com.fsck.k9.search_folder"

        private const val STATE_DISPLAY_MODE = "displayMode"
        private const val STATE_MESSAGE_LIST_WAS_DISPLAYED = "messageListWasDisplayed"
        private const val STATE_FIRST_BACK_STACK_ID = "firstBackstackId"

        private const val FRAGMENT_TAG_MESSAGE_VIEW = "MessageViewFragment"
        private const val FRAGMENT_TAG_PLACEHOLDER = "MessageViewPlaceholder"

        // Used for navigating to next/previous message
        private const val PREVIOUS = 1
        private const val NEXT = 2

        const val REQUEST_MASK_PENDING_INTENT = 1 shl 15

        private val defaultFolderProvider: DefaultFolderProvider by inject()

        @JvmStatic
        @JvmOverloads
        fun actionDisplaySearch(
            context: Context,
            search: SearchSpecification?,
            noThreading: Boolean,
            newTask: Boolean,
            clearTop: Boolean = true
        ) {
            context.startActivity(intentDisplaySearch(context, search, noThreading, newTask, clearTop))
        }

        @JvmStatic
        fun intentDisplaySearch(
            context: Context?,
            search: SearchSpecification?,
            noThreading: Boolean,
            newTask: Boolean,
            clearTop: Boolean
        ): Intent {
            return Intent(context, MessageList::class.java).apply {
                putExtra(EXTRA_SEARCH, ParcelableUtil.marshall(search))
                putExtra(EXTRA_NO_THREADING, noThreading)

                addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP)

                if (clearTop) addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP)
                if (newTask) addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
        }

        @JvmStatic
        fun shortcutIntent(context: Context?, specialFolder: String?): Intent {
            return Intent(context, MessageList::class.java).apply {
                action = ACTION_SHORTCUT
                putExtra(EXTRA_SPECIAL_FOLDER, specialFolder)

                addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP)
                addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP)
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
        }

        @JvmStatic
        fun shortcutIntentForAccount(context: Context?, account: Account): Intent {
            val folderId = defaultFolderProvider.getDefaultFolder(account)

            val search = LocalSearch().apply {
                addAccountUuid(account.uuid)
                addAllowedFolder(folderId)
            }

            return intentDisplaySearch(context, search, noThreading = false, newTask = true, clearTop = true)
        }

        @JvmStatic
        fun actionDisplayMessageIntent(context: Context?, messageReference: MessageReference): Intent {
            return Intent(context, MessageList::class.java).apply {
                addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP)
                addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP)
                putExtra(EXTRA_MESSAGE_REFERENCE, messageReference.toIdentityString())
            }
        }

        @JvmStatic
        fun launch(context: Context) {
            val intent = Intent(context, MessageList::class.java).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP)
                addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP)
                addFlags(Intent.FLAG_ACTIVITY_CLEAR_TASK)
            }

            context.startActivity(intent)
        }

        @JvmStatic
        fun launch(context: Context, account: Account) {
            val folderId = defaultFolderProvider.getDefaultFolder(account)

            val search = LocalSearch().apply {
                addAllowedFolder(folderId)
                addAccountUuid(account.uuid)
            }

            actionDisplaySearch(context, search, noThreading = false, newTask = false)
        }
    }
}
