package io.codemyriad.cassini.flows

import androidx.compose.runtime.MutableState
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsOff
import androidx.compose.ui.test.assertIsOn
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.isToggleable
import androidx.compose.ui.test.junit4.StateRestorationTester
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import io.codemyriad.cassini.R
import io.codemyriad.cassini.background.Background
import io.codemyriad.cassini.test.CiSmoke
import io.codemyriad.cassini.transfer.BulkExportButton
import io.codemyriad.cassini.transfer.BulkTransferControls
import io.codemyriad.cassini.transfer.BulkTransfers
import io.codemyriad.cassini.ui.theme.CassiniTheme
import org.junit.Assert.assertNull
import org.junit.Rule
import org.junit.Test

/** A recreated cold query has no value yet; that is distinct from an empty loaded catalogue. */
@CiSmoke
class BulkTransferRestorationTest {
    @get:Rule val rule = createComposeRule()

    @Test fun aPartialSelectionSurvivesRestorationWhileTheCatalogueReloads() {
        val restoration = StateRestorationTester(rule)
        val choices = listOf(BulkTransfers.Choice("a", "Alpha"), BulkTransfers.Choice("b", "Beta"))
        var restoring = false
        lateinit var loaded: MutableState<List<BulkTransfers.Choice>?>
        val context = Background.context
        restoration.setContent {
            loaded = remember { mutableStateOf(if (restoring) null else choices) }
            CassiniTheme {
                BulkTransferControls(
                    Background.graph.transfers,
                    BulkTransfers.TransferState(),
                    loaded.value,
                ) { enabled, open ->
                    BulkExportButton(enabled, open)
                }
            }
        }
        rule.onNodeWithText(context.getString(R.string.export_zip_all)).performClick()
        val alpha = hasText("Alpha") and isToggleable()
        val beta = hasText("Beta") and isToggleable()
        rule.onNode(alpha).performClick()
        rule.onNode(alpha).assertIsOff()
        rule.onNode(beta).assertIsOn()

        restoring = true
        restoration.emulateSavedInstanceStateRestore()
        rule.runOnIdle { assertNull("Recreation must exercise a not-yet-loaded catalogue", loaded.value) }
        rule.onNodeWithText(context.getString(R.string.export_zip_selected)).assertIsDisplayed()
        rule.runOnIdle { loaded.value = choices }
        rule.onNode(alpha).assertIsOff()
        rule.onNode(beta).assertIsOn()
    }
}
