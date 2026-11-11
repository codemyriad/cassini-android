package io.codemyriad.cassini.transfer

import android.content.Context
import android.content.Intent
import androidx.activity.result.contract.ActivityResultContracts
import io.codemyriad.cassini.R

/** Android chooses MIME filters itself; pickers that show a supplied title use the shared D96 label. */
class BulkImportContract : ActivityResultContracts.OpenMultipleDocuments() {
    override fun createIntent(context: Context, input: Array<String>): Intent =
        super.createIntent(context, input).putExtra(
            Intent.EXTRA_TITLE,
            context.getString(R.string.import_filter_cassini_and_zip),
        )
}
