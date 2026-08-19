package com.inscreen.mic

internal object ModuleBackPolicy {
    const val DISPATCH_SCRIPT =
        "(()=>{const event=new CustomEvent('inscreen:atras',{cancelable:true});window.dispatchEvent(event);return event.defaultPrevented})()"

    fun wasConsumed(result: String?): Boolean = result == "true"
}
