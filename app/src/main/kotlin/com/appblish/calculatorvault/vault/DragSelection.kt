package com.appblish.calculatorvault.vault

/**
 * Pure range math for long-press-drag multi-select (W1-E3, design S17): during one drag
 * gesture the selection is always the drag-start snapshot plus the anchor-to-target range
 * in **display order** — drag forward and the range grows, drag back and items the gesture
 * had swept up (but that were not selected before it began) drop out again, exactly the
 * photo-picker behaviour users know. Pure Kotlin so the semantics are JVM-unit-testable.
 */
object DragSelection {
    /**
     * The selection while a drag from [anchor] is hovering [target]. [ordered] is the
     * grid's display order; [base] is the selection snapshot taken when the drag began
     * (anchor included). Unknown anchor/target ids degrade to keeping [base] — a vanished
     * item mid-gesture must never wipe the user's selection.
     */
    fun rangeSelect(
        ordered: List<String>,
        base: Set<String>,
        anchor: String,
        target: String,
    ): Set<String> {
        val anchorIndex = ordered.indexOf(anchor)
        val targetIndex = ordered.indexOf(target)
        if (anchorIndex < 0 || targetIndex < 0) return base
        val range = ordered.subList(minOf(anchorIndex, targetIndex), maxOf(anchorIndex, targetIndex) + 1)
        return base + range
    }
}
