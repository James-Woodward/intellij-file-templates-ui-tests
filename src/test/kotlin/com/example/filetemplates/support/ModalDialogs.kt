package com.example.filetemplates.support

import com.intellij.driver.client.Driver
import com.intellij.driver.client.Remote
import com.intellij.driver.client.impl.JmxHost
import com.intellij.driver.model.OnDispatcher
import com.intellij.driver.sdk.ui.accessibleName
import com.intellij.driver.sdk.ui.components.UiComponent
import com.intellij.driver.sdk.ui.components.elements.WindowUiComponent
import com.intellij.driver.sdk.ui.ui
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
 * Closes the dialog titled [title], if it is open.
 *
 * Used to recover from a failure while a modal dialog is up. Leaving one open is not a cosmetic
 * problem: the IDE cannot shut down with a modal dialog on screen, so the run stops responding at
 * the end rather than reporting the failure that caused it.
 */
private fun Driver.disposeDialog(title: String) {
    runCatching {
        val dialog = ui.x("//div[@class='MyDialog' and @title='$title']", WindowUiComponent::class.java)
        if (dialog.present()) {
            val window = cast(dialog.component, DisposableWindowRef::class)
            withContext(OnDispatcher.EDT) { window.dispose() }
        }
    }
}

/**
 * Runs [openModal] -- an interaction that opens a modal dialog, and therefore blocks -- while
 * [handleDialog] drives that dialog over a separate connection.
 *
 * [expectedDialogTitle] names the dialog so it can be closed if [handleDialog] fails, which keeps a
 * failing test from leaving the IDE stuck behind a dialog nothing will dismiss.
 */
fun Driver.withModalDialog(
    expectedDialogTitle: String,
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
            // Close the dialog before giving up: the opener is blocked inside it and the IDE
            // cannot shut down while it is on screen, so leaving it would replace this failure
            // with an unexplained hang at the end of the run.
            second.disposeDialog(expectedDialogTitle)

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
