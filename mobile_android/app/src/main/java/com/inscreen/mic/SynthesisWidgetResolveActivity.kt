package com.inscreen.mic

import android.app.Activity
import android.content.Intent
import android.os.Bundle
import android.widget.Toast
import org.json.JSONObject

class SynthesisWidgetResolveActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val subjectId = runCatching {
            JSONObject(AprioriStore.load(this)).optJSONArray("ring")?.optString(0).orEmpty()
        }.getOrDefault("")
        if (subjectId.isBlank()) {
            Toast.makeText(this, R.string.synthesis_widget_no_subject, Toast.LENGTH_LONG).show()
            startActivity(Intent(this, MainActivity::class.java))
        } else {
            ModuleHostActivity.openRefreshing(this, subjectId, SYNTHESIS_MODULE)
        }
        finish()
    }

    companion object {
        internal val SYNTHESIS_MODULE = ModuleCatalog.Module("sintesis", "Síntesis", "modules/sintesis/index.html")
    }
}
