package eu.kanade.tachiyomi.ui.main

import android.app.SearchManager
import android.content.Intent
import android.os.Bundle
import android.view.ViewGroup
import androidx.core.view.GravityCompat
import com.bluelinelabs.conductor.Conductor
import com.bluelinelabs.conductor.Controller
import com.bluelinelabs.conductor.ControllerChangeHandler
import com.bluelinelabs.conductor.Router
import com.bluelinelabs.conductor.RouterTransaction
import eu.kanade.tachiyomi.Migrations
import eu.kanade.tachiyomi.R
import eu.kanade.tachiyomi.data.notification.NotificationReceiver
import eu.kanade.tachiyomi.data.preference.getOrDefault
import eu.kanade.tachiyomi.extension.api.ExtensionGithubApi
import eu.kanade.tachiyomi.ui.base.activity.BaseActivity
import eu.kanade.tachiyomi.ui.base.controller.DialogController
import eu.kanade.tachiyomi.ui.base.controller.NoToolbarElevationController
import eu.kanade.tachiyomi.ui.base.controller.RootController
import eu.kanade.tachiyomi.ui.base.controller.SecondaryDrawerController
import eu.kanade.tachiyomi.ui.base.controller.TabbedController
import eu.kanade.tachiyomi.ui.base.controller.withFadeTransaction
import eu.kanade.tachiyomi.ui.catalogue.CatalogueController
import eu.kanade.tachiyomi.ui.catalogue.global_search.CatalogueSearchController
import eu.kanade.tachiyomi.ui.download.DownloadController
import eu.kanade.tachiyomi.ui.extension.ExtensionController
import eu.kanade.tachiyomi.ui.library.LibraryController
import eu.kanade.tachiyomi.ui.manga.MangaController
import eu.kanade.tachiyomi.ui.more.MoreController
import eu.kanade.tachiyomi.ui.recent.history.HistoryController
import eu.kanade.tachiyomi.ui.recent.updates.UpdatesController
import java.util.Date
import java.util.concurrent.TimeUnit
import kotlinx.android.synthetic.main.main_activity.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.GlobalScope
import kotlinx.coroutines.launch
import timber.log.Timber

class MainActivity : BaseActivity() {

    private lateinit var router: Router

    private var secondaryDrawer: ViewGroup? = null

    private val startScreenId by lazy {
        when (preferences.startScreen()) {
            2 -> R.id.nav_history
            3 -> R.id.nav_updates
            else -> R.id.nav_library
        }
    }

    lateinit var tabAnimator: ViewHeightAnimator
    private lateinit var bottomNavAnimator: ViewHeightAnimator

    private var isHandlingShortcut: Boolean = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // Do not let the launcher create a new activity http://stackoverflow.com/questions/16283079
        if (!isTaskRoot) {
            finish()
            return
        }

        setContentView(R.layout.main_activity)

        setSupportActionBar(toolbar)

        tabAnimator = ViewHeightAnimator(tabs)
        bottomNavAnimator = ViewHeightAnimator(bottom_nav)

        // Set behavior of bottom nav
        bottom_nav.setOnNavigationItemSelectedListener { item ->
            val id = item.itemId

            val currentRoot = router.backstack.firstOrNull()
            if (currentRoot?.tag()?.toIntOrNull() != id) {
                when (id) {
                    R.id.nav_library -> setRoot(LibraryController(), id)
                    R.id.nav_updates -> setRoot(UpdatesController(), id)
                    R.id.nav_history -> setRoot(HistoryController(), id)
                    R.id.nav_sources -> setRoot(CatalogueController(), id)
                    R.id.nav_more -> setRoot(MoreController(), id)
                }
            } else if (!isHandlingShortcut) {
                when (id) {
                    R.id.nav_library -> {
                        val controller = router.getControllerWithTag(id.toString()) as? LibraryController
                        controller?.showSettingsSheet()
                    }
                }
            }
            true
        }

        val container: ViewGroup = findViewById(R.id.controller_container)

        router = Conductor.attachRouter(this, container, savedInstanceState)
        if (!router.hasRootController()) {
            // Set start screen
            if (!handleIntentAction(intent)) {
                setSelectedDrawerItem(startScreenId)
            }
        }

        toolbar.setNavigationOnClickListener {
            if (router.backstackSize == 1) {
                drawer.openDrawer(GravityCompat.START)
            } else {
                onBackPressed()
            }
        }

        router.addChangeListener(object : ControllerChangeHandler.ControllerChangeListener {
            override fun onChangeStarted(
                to: Controller?,
                from: Controller?,
                isPush: Boolean,
                container: ViewGroup,
                handler: ControllerChangeHandler
            ) {
                syncActivityViewWithController(to, from)
            }

            override fun onChangeCompleted(
                to: Controller?,
                from: Controller?,
                isPush: Boolean,
                container: ViewGroup,
                handler: ControllerChangeHandler
            ) {
            }
        })

        syncActivityViewWithController(router.backstack.lastOrNull()?.controller())

        if (savedInstanceState == null) {
            // Show changelog if needed
            if (Migrations.upgrade(preferences)) {
                ChangelogDialogController().showDialog(router)
            }
        }
        preferences.extensionUpdatesCount().asObservable().subscribe {
            setExtensionsBadge()
        }
        setExtensionsBadge()
    }

    override fun onNewIntent(intent: Intent) {
        if (!handleIntentAction(intent)) {
            super.onNewIntent(intent)
        }
    }

    override fun onResume() {
        super.onResume()
        getExtensionUpdates()
    }

    private fun setExtensionsBadge() {
        val updates = preferences.extensionUpdatesCount().getOrDefault()
        if (updates > 0) {
            bottom_nav.getOrCreateBadge(R.id.nav_more).number = updates
        } else {
            bottom_nav.removeBadge(R.id.nav_more)
        }
    }

    private fun getExtensionUpdates() {
        // Limit checks to once a day at most
        val now = Date().time
        if (now < preferences.lastExtCheck().getOrDefault() + TimeUnit.DAYS.toMillis(1)) {
            return
        }

        GlobalScope.launch(Dispatchers.IO) {
            try {
                val pendingUpdates = ExtensionGithubApi().checkForUpdates(this@MainActivity)
                preferences.extensionUpdatesCount().set(pendingUpdates.size)
            } catch (e: Exception) {
                Timber.e(e)
            }
        }
    }

    private fun handleIntentAction(intent: Intent): Boolean {
        val notificationId = intent.getIntExtra("notificationId", -1)
        if (notificationId > -1) {
            NotificationReceiver.dismissNotification(applicationContext, notificationId, intent.getIntExtra("groupId", 0))
        }

        isHandlingShortcut = true

        when (intent.action) {
            SHORTCUT_LIBRARY -> setSelectedDrawerItem(R.id.nav_library)
            SHORTCUT_RECENTLY_UPDATED -> setSelectedDrawerItem(R.id.nav_updates)
            SHORTCUT_RECENTLY_READ -> setSelectedDrawerItem(R.id.nav_history)
            SHORTCUT_CATALOGUES -> setSelectedDrawerItem(R.id.nav_sources)
            SHORTCUT_EXTENSIONS -> {
                if (router.backstackSize > 1) {
                    router.popToRoot()
                }
                setSelectedDrawerItem(R.id.nav_more)
                router.pushController(ExtensionController().withFadeTransaction())
            }
            SHORTCUT_MANGA -> {
                val extras = intent.extras ?: return false
                if (router.backstackSize > 1) {
                    router.popToRoot()
                }
                setSelectedDrawerItem(R.id.nav_library)
                router.pushController(RouterTransaction.with(MangaController(extras)))
            }
            SHORTCUT_DOWNLOADS -> {
                if (router.backstackSize > 1) {
                    router.popToRoot()
                }
                setSelectedDrawerItem(R.id.nav_more)
                router.pushController(RouterTransaction.with(DownloadController()))
            }
            Intent.ACTION_SEARCH, "com.google.android.gms.actions.SEARCH_ACTION" -> {
                // If the intent match the "standard" Android search intent
                // or the Google-specific search intent (triggered by saying or typing "search *query* on *Tachiyomi*" in Google Search/Google Assistant)

                // Get the search query provided in extras, and if not null, perform a global search with it.
                val query = intent.getStringExtra(SearchManager.QUERY)
                if (query != null && query.isNotEmpty()) {
                    if (router.backstackSize > 1) {
                        router.popToRoot()
                    }
                    router.pushController(CatalogueSearchController(query).withFadeTransaction())
                }
            }
            INTENT_SEARCH -> {
                val query = intent.getStringExtra(INTENT_SEARCH_QUERY)
                val filter = intent.getStringExtra(INTENT_SEARCH_FILTER)
                if (query != null && query.isNotEmpty()) {
                    if (router.backstackSize > 1) {
                        router.popToRoot()
                    }
                    router.pushController(CatalogueSearchController(query, filter).withFadeTransaction())
                }
            }
            else -> {
                isHandlingShortcut = false
                return false
            }
        }

        isHandlingShortcut = false
        return true
    }

    override fun onDestroy() {
        super.onDestroy()
        bottom_nav?.setOnNavigationItemSelectedListener(null)
        toolbar?.setNavigationOnClickListener(null)
    }

    override fun onBackPressed() {
        val backstackSize = router.backstackSize
        if (drawer.isDrawerOpen(GravityCompat.START) || drawer.isDrawerOpen(GravityCompat.END)) {
            drawer.closeDrawers()
        } else if (backstackSize == 1 && router.getControllerWithTag("$startScreenId") == null) {
            setSelectedDrawerItem(startScreenId)
        } else if (backstackSize == 1 || !router.handleBack()) {
            super.onBackPressed()
        }
    }

    private fun setSelectedDrawerItem(itemId: Int) {
        if (!isFinishing) {
            bottom_nav.selectedItemId = itemId
        }
    }

    private fun setRoot(controller: Controller, id: Int) {
        router.setRoot(controller.withFadeTransaction().tag(id.toString()))
    }

    private fun syncActivityViewWithController(to: Controller?, from: Controller? = null) {
        if (from is DialogController || to is DialogController) {
            return
        }

        supportActionBar?.setDisplayHomeAsUpEnabled(router.backstackSize != 1)

        if ((from == null || from is RootController) && to !is RootController) {
            bottomNavAnimator.collapse()
        }
        if (to is RootController && from !is RootController) {
            bottomNavAnimator.expand()
        }

        if (from is TabbedController) {
            from.cleanupTabs(tabs)
        }
        if (to is TabbedController) {
            tabAnimator.expand()
            to.configureTabs(tabs)
        } else {
            tabAnimator.collapse()
            tabs.setupWithViewPager(null)
        }

        if (from is SecondaryDrawerController) {
            if (secondaryDrawer != null) {
                from.cleanupSecondaryDrawer(drawer)
                drawer.removeView(secondaryDrawer)
                secondaryDrawer = null
            }
        }
        if (to is SecondaryDrawerController) {
            secondaryDrawer = to.createSecondaryDrawer(drawer)?.also { drawer.addView(it) }
        }

        if (to is NoToolbarElevationController) {
            appbar.disableElevation()
        } else {
            appbar.enableElevation()
        }
    }

    companion object {
        // Shortcut actions
        const val SHORTCUT_LIBRARY = "eu.kanade.tachiyomi.SHOW_LIBRARY"
        const val SHORTCUT_RECENTLY_UPDATED = "eu.kanade.tachiyomi.SHOW_RECENTLY_UPDATED"
        const val SHORTCUT_RECENTLY_READ = "eu.kanade.tachiyomi.SHOW_RECENTLY_READ"
        const val SHORTCUT_CATALOGUES = "eu.kanade.tachiyomi.SHOW_CATALOGUES"
        const val SHORTCUT_DOWNLOADS = "eu.kanade.tachiyomi.SHOW_DOWNLOADS"
        const val SHORTCUT_MANGA = "eu.kanade.tachiyomi.SHOW_MANGA"
        const val SHORTCUT_EXTENSIONS = "eu.kanade.tachiyomi.EXTENSIONS"

        const val INTENT_SEARCH = "eu.kanade.tachiyomi.SEARCH"
        const val INTENT_SEARCH_QUERY = "query"
        const val INTENT_SEARCH_FILTER = "filter"
    }
}
