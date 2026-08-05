package com.inscreen.mic

import android.text.Editable
import android.text.TextWatcher

internal class SimpleTextWatcher(private val changed: (String) -> Unit) : TextWatcher {
    override fun beforeTextChanged(value: CharSequence?, start: Int, count: Int, after: Int) = Unit
    override fun onTextChanged(value: CharSequence?, start: Int, before: Int, count: Int) = changed(value?.toString().orEmpty())
    override fun afterTextChanged(value: Editable?) = Unit
}
