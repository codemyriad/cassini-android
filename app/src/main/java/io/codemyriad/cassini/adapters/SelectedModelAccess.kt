package io.codemyriad.cassini.adapters

import io.codemyriad.cassini.core.models.ModelPart
import io.codemyriad.cassini.core.models.ModelPlan
import io.codemyriad.cassini.data.settings.SpeakerChoices
import io.codemyriad.cassini.engine.job.ModelAccess

/** A job snapshots the processing choices; changing a switch cannot alter a running native inference. */
class SelectedModelAccess(
    private val delegate: ModelAccess,
    private val choices: SpeakerChoices,
) : ModelAccess by delegate {
    override fun ready(part: ModelPart): Boolean = choices.enabled(part) && delegate.ready(part)

    override fun plan(): ModelPlan = ModelPlan(delegate.plan().missing.filter { choices.enabled(it.part) })
}
