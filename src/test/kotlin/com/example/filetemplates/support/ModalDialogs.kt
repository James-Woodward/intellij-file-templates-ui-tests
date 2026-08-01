package com.example.filetemplates.support

import com.intellij.driver.client.Driver
import com.intellij.driver.client.Remote
import com.intellij.driver.client.impl.JmxHost
import com.intellij.driver.model.OnDispatcher
import com.intellij.driver.sdk.ui.accessibleName
import com.intellij.driver.sdk.ui.components.UiComponent
import com.intellij.driver.sdk.ui.components.elements.WindowUiComponent
import com.intellij.driver.sdk.ui.remote.SwingHierarchyService
import com.intellij.driver.sdk.ui.ui
import com.intellij.ide.starter.ide.IDETestContext
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.atomic.AtomicReference
import kotlin.concurrent.thread

/**
 * Driving **modal** dialogs without the mouse.
 *
 * A modal dialog is the one case [DirectInteraction] cannot handle on its own. Asking a control to
 * open one does not return until the dialog is dismissed, and every call made through a Driver
 * passes through a single `synchronized` JMX invocation handler -- so while that call is
 * outstanding, nothing else on that client gets through either. Opening the dialog on a second
 * thread does not help: the thread blocks holding the lock, and the next query simply queues
 * behind it. (Verified: a hierarchy query issued in that state never returns.)
 *
 * The way out is a second client. Each Driver owns its own invocation handler and its own JMX
 * connection, so one connection can sit blocked inside the dialog while the other drives it.
 */

@Remote("java.lang.System")
interface SystemPropertiesRef {
    fun getProperty(key: String): String?
}

@Remote("com.intellij.openapi.util.registry.Registry")
interface RegistryRef {
    fun get(key: String): RegistryValueRef?
}

@Remote("com.intellij.openapi.util.registry.RegistryValue")
interface RegistryValueRef {
    fun asBoolean(): Boolean
    fun setValue(value: Boolean)
}

/** Registry key deciding whether macOS confirmations are native sheets rather than Swing dialogs. */
private const val MAC_SHEETS_KEY = "ide.mac.message.dialogs.as.sheets"

/**
 * Starts the IDE with confirmations as Swing dialogs rather than native macOS alerts.
 *
 * Set before launch, as a system property the registry reads, rather than changed afterwards: the
 * key only exists on macOS, so reading it at runtime fails outright on other platforms, and the
 * setting is wanted from the moment the IDE starts. Ignored where it means nothing.
 */
fun IDETestContext.preferSwingDialogs(): IDETestContext =
    applyVMOptionsPatch { addSystemProperty(MAC_SHEETS_KEY, false) }

/**
 * Asks the IDE to show its confirmations as ordinary Swing dialogs.
 *
 * On macOS IntelliJ renders `Messages` confirmations as native alerts. A native alert is not part
 * of the Swing hierarchy, so it cannot be found, read or dismissed through the Driver at all: the
 * dialog is plainly on screen while every query reports nothing there, and because it is modal the
 * IDE cannot then be shut down either. Turning this off makes the same confirmation a Swing dialog,
 * which is reachable like any other component.
 *
 * Returns a description of what happened, for reporting: the key is macOS-only and may be absent or
 * renamed in a given build, and none of that should stop a run on a platform that never needed it.
 */
fun Driver.useSwingDialogsForMessages(): String =
    runCatching {
        val value = utility(RegistryRef::class).get(MAC_SHEETS_KEY)
            ?: return@runCatching "'$MAC_SHEETS_KEY' not present in this build"
        val before = value.asBoolean()
        if (before) {
            withContext(OnDispatcher.EDT) { value.setValue(false) }
        }
        "'$MAC_SHEETS_KEY' was $before, now ${value.asBoolean()}"
    }.getOrElse { "could not read '$MAC_SHEETS_KEY': ${it.message}" }

/**
 * Waits for a button named as one of [names] and returns it.
 *
 * Written in terms of "any of these" rather than one fixed label because a confirmation's wording
 * is exactly the kind of thing that differs between IDE versions. When none of them turns up, the
 * failure lists the buttons that were actually on screen, which is the information needed to fix
 * the call -- far more useful than "component not found".
 */
fun Driver.awaitButton(
    vararg names: String,
    inDialogTitled: String? = null,
    timeoutMs: Long = 15_000,
): UiComponent {
    val wanted = names.toSet()
    // Scoped to the dialog when its title is known, so the buttons of whatever is behind it -- the
    // Settings dialog has OK, Cancel and Apply -- cannot be mistaken for the ones being waited for.
    val scope = inDialogTitled?.let { "//div[@class='MyDialog' and @title='$it']//div[@class='JButton']" }
        ?: "//div[@class='JButton']"

    var seen: List<String> = emptyList()
    val deadline = System.currentTimeMillis() + timeoutMs

    while (System.currentTimeMillis() < deadline) {
        val buttons = runCatching { ui.xx(scope).list() }.getOrDefault(emptyList())
        seen = buttons.mapNotNull { button -> runCatching { button.accessibleName }.getOrNull() }
        val match = buttons.firstOrNull { button ->
            runCatching { button.accessibleName in wanted }.getOrDefault(false)
        }
        if (match != null) return match
        Thread.sleep(300)
    }

    // Falling back to a global list turns "not in that dialog" into "here is everything there was",
    // which is what tells us whether the dialog was missing or merely worded differently.
    val everything = runCatching {
        ui.xx("//div[@class='JButton']").list()
            .mapNotNull { runCatching { it.accessibleName }.getOrNull() }
    }.getOrDefault(emptyList())

    error(
        "none of ${wanted.toList()} appeared within ${timeoutMs}ms" +
            (inDialogTitled?.let { " in a dialog titled '$it'" } ?: "") +
            "; buttons in scope: $seen; all buttons on screen: $everything",
    )
}

/**
 * Opens a second, independent connection to the same IDE.
 *
 * The port is read back from the IDE rather than assumed: IDE Starter picks it at launch (7777 when
 * free, another port otherwise) and passes it to the IDE as a system property, so asking the IDE
 * which port it was given is the only reliable way to find it.
 */
fun Driver.openSecondConnection(): Driver {
    val port = utility(SystemPropertiesRef::class).getProperty("com.sun.management.jmxremote.port")
        ?: error("the IDE exposes no JMX port, so a second driver connection cannot be opened")
    return Driver.create(JmxHost(address = "127.0.0.1:$port"))
}

/**
 * Runs [openModal] -- an interaction that opens a modal dialog, and therefore blocks -- while
 * [handleDialog] drives that dialog over a separate connection.
 *
 * [handleDialog] is given the second [Driver]. Every lookup used to deal with the dialog has to go
 * through it; the original connection stays blocked until the dialog closes.
 */
@Remote("java.awt.Window")
interface DisposableWindowRef {
    fun dispose()
}

/**
 * Closes every dialog that is open, whatever it is called.
 *
 * Used to recover from a failure while a modal dialog is up, and deliberately indiscriminate: the
 * IDE cannot shut down while a modal dialog is on screen, so one left behind replaces the failure
 * that caused it with a run that stops responding. Identifying dialogs by title was not good
 * enough -- a confirmation rendered as a native alert has no title to match on -- and by this
 * point the test has failed anyway, so closing too much costs nothing.
 */
private fun Driver.disposeAllDialogs() {
    runCatching {
        ui.xx("//div[@class='MyDialog']").list().forEach { dialog ->
            runCatching {
                val window = cast(dialog.component, DisposableWindowRef::class)
                withContext(OnDispatcher.EDT) { window.dispose() }
            }
        }
    }
}

/**
 * The buttons of every dialog on screen, as `dialog -> [button, ...]`.
 *
 * Reported when a dialog cannot be dealt with. Labels and titles vary between platforms and IDE
 * versions far more than the structure does, so the useful thing to record on failure is what was
 * actually there rather than a note that something expected was missing.
 */
private fun Driver.describeDialogs(): String =
    runCatching {
        ui.xx("//div[@class='MyDialog']").list().joinToString("; ") { dialog ->
            val title = runCatching { dialog.accessibleName }.getOrNull() ?: "<untitled>"
            val buttons = runCatching {
                dialog.xx("//div[@class='JButton']").list()
                    .mapNotNull { runCatching { it.accessibleName }.getOrNull() }
            }.getOrDefault(emptyList())
            "'$title' -> $buttons"
        }
    }.getOrDefault("<could not read dialogs>")

/**
 * Writes the live Swing tree to `out/ui-dump/<name>.xml`, plus a one-line summary alongside it.
 *
 * A file, rather than a message on the failure, because the failures this is for are the ones that
 * stop the run from finishing: a modal dialog that cannot be dismissed keeps the IDE alive, the
 * test never returns, and nothing is reported. Whatever is on screen is recorded while it still
 * can be, and a dialog missing from this dump is not a Swing component at all.
 */
fun Driver.dumpUiState(name: String) {
    runCatching {
        val outDir = Path.of("out", "ui-dump")
        Files.createDirectories(outDir)

        val dom = service(SwingHierarchyService::class).getSwingHierarchyAsDOM(null, false)
        Files.writeString(outDir.resolve("$name.xml"), dom)

        val summary = buildString {
            appendLine("dialogs: ${describeDialogs()}")
            appendLine("windows in tree: " + Regex("class=\"([A-Za-z]*(Dialog|Window|Frame)[A-Za-z]*)\"")
                .findAll(dom).map { it.groupValues[1] }.distinct().joinToString(", "))
        }
        Files.writeString(outDir.resolve("$name-summary.txt"), summary)
    }
}

/** The titles of the dialogs currently open, used as a baseline for [awaitNewDialogButton]. */
fun Driver.dialogTitles(): Set<String> =
    runCatching {
        ui.xx("//div[@class='MyDialog']").list()
            .mapNotNull { runCatching { it.accessibleName }.getOrNull() }
            .toSet()
    }.getOrDefault(emptySet())

/**
 * Waits for a dialog that was not already open, and returns the button that accepts it.
 *
 * Identified by having appeared, rather than by what it is called or what it contains. Titles and
 * button labels vary between IDE versions and platforms -- the confirmation for reverting a
 * template accepts on a button labelled "OK", not "Reset" -- and describing the dialog structurally
 * proved worse than useless: a check meant to exclude the Settings dialog did not, so its own OK
 * was pressed instead, quietly applying the change that was supposed to be discarded. What is
 * dependable is that the dialog was not there a moment ago.
 *
 * The accepting button is chosen by exclusion, for the same reason: anything that is not a Cancel,
 * No, Help or Close.
 */
fun Driver.awaitNewDialogButton(alreadyOpen: Set<String>, timeoutMs: Long = 15_000): UiComponent {
    val dismissive = setOf("Cancel", "No", "Help", "Close")
    val deadline = System.currentTimeMillis() + timeoutMs

    while (System.currentTimeMillis() < deadline) {
        val appeared = runCatching {
            ui.xx("//div[@class='MyDialog']").list().firstOrNull { dialog ->
                runCatching { dialog.accessibleName !in alreadyOpen }.getOrDefault(false)
            }
        }.getOrNull()

        if (appeared != null) {
            val accept = runCatching {
                appeared.xx("//div[@class='JButton']").list().firstOrNull { button ->
                    runCatching { button.accessibleName !in dismissive }.getOrDefault(false)
                }
            }.getOrNull()
            if (accept != null) return accept
        }
        Thread.sleep(300)
    }

    error(
        "no dialog beyond $alreadyOpen appeared within ${timeoutMs}ms; dialogs on screen: ${describeDialogs()}",
    )
}

/**
 * Runs [openModal] -- an interaction that opens a modal dialog, and therefore blocks -- while
 * [handleDialog] drives that dialog over a separate connection.
 *
 * If [handleDialog] fails, every open dialog is closed before the failure is rethrown, so a test
 * that could not deal with a dialog still reports why instead of leaving the IDE unable to exit.
 */
fun Driver.withModalDialog(
    openModal: () -> Unit,
    handleDialog: (Driver) -> Unit,
) {
    // Opened before the blocking call starts: obtaining it needs the first connection to be free.
    openSecondConnection().use { second ->
        val failure = AtomicReference<Throwable?>(null)
        val opener = thread(start = true, isDaemon = true, name = "modal-dialog-opener") {
            try {
                openModal()
            } catch (t: Throwable) {
                failure.set(t)
            }
        }

        try {
            handleDialog(second)
        } catch (dialogFailure: Throwable) {
            // Written before anything else, because what follows may not survive: a dialog that
            // cannot be dismissed leaves the IDE unable to exit, the test never completes, and
            // Gradle writes no report at all -- so the failure would otherwise leave no trace.
            second.dumpUiState("modal-dialog-not-handled")
            // Close the dialogs before giving up: the opener is blocked inside one and the IDE
            // cannot shut down while it is on screen, so leaving it would replace this failure
            // with an unexplained hang at the end of the run.
            second.disposeAllDialogs()

            // When the opener is what failed, "the dialog never appeared" is only the symptom;
            // report the cause and keep the symptom attached to it.
            opener.join(5_000)
            failure.get()?.let { openerFailure ->
                throw IllegalStateException("the action opening the modal dialog failed", openerFailure)
                    .apply { addSuppressed(dialogFailure) }
            }
            throw dialogFailure
        }

        // Dismissing the dialog releases the opener; joining makes sure its failure is not lost.
        opener.join(30_000)
        failure.get()?.let {
            throw IllegalStateException("the action opening the modal dialog failed", it)
        }
    }
}
