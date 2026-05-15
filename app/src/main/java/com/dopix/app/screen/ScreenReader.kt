package com.dopix.app.screen

import android.graphics.Rect
import android.view.accessibility.AccessibilityNodeInfo

/**
 * Reads the accessibility node tree recursively and builds a structured
 * text representation of everything visible on screen.
 */
class ScreenReader {

    data class ScreenElement(
        val text: String,
        val className: String,
        val bounds: Rect,
        val isClickable: Boolean,
        val isEditable: Boolean,
        val isScrollable: Boolean,
        val contentDescription: String?,
        val viewId: String?,
        val children: List<ScreenElement>,
        val depth: Int
    )

    /**
     * Read all meaningful UI elements from the accessibility tree.
     */
    fun readScreen(root: AccessibilityNodeInfo?): List<ScreenElement> {
        if (root == null) return emptyList()
        return parseNode(root, 0)
    }

    private fun parseNode(node: AccessibilityNodeInfo, depth: Int): List<ScreenElement> {
        val elements = mutableListOf<ScreenElement>()
        val bounds = Rect()
        node.getBoundsInScreen(bounds)

        val children = mutableListOf<ScreenElement>()
        for (i in 0 until node.childCount) {
            node.getChild(i)?.let { child ->
                children.addAll(parseNode(child, depth + 1))
                child.recycle()
            }
        }

        val text = node.text?.toString() ?: ""
        val contentDesc = node.contentDescription?.toString()
        val className = node.className?.toString() ?: ""

        if (text.isNotEmpty() || contentDesc != null || node.isClickable || node.isEditable) {
            elements.add(
                ScreenElement(
                    text = text,
                    className = className,
                    bounds = bounds,
                    isClickable = node.isClickable,
                    isEditable = node.isEditable,
                    isScrollable = node.isScrollable,
                    contentDescription = contentDesc,
                    viewId = node.viewIdResourceName,
                    children = children,
                    depth = depth
                )
            )
        } else {
            elements.addAll(children)
        }

        return elements
    }

    /**
     * Convert screen elements to a human-readable text representation
     * suitable for sending to an LLM.
     */
    fun screenToText(elements: List<ScreenElement>): String {
        val sb = StringBuilder()
        sb.appendLine("=== SCREEN CONTENT ===")
        for ((index, el) in elements.withIndex()) {
            val indent = "  ".repeat(el.depth.coerceAtMost(4))
            val type = when {
                el.isEditable -> "[INPUT]"
                el.isClickable -> "[BUTTON]"
                el.isScrollable -> "[SCROLLABLE]"
                el.className.contains("ImageView") -> "[IMAGE]"
                else -> "[TEXT]"
            }
            val displayText = when {
                el.text.isNotEmpty() -> el.text
                el.contentDescription != null -> "(${el.contentDescription})"
                else -> ""
            }
            if (displayText.isNotEmpty()) {
                sb.appendLine("$indent#$index $type $displayText [${el.bounds.centerX()},${el.bounds.centerY()}]")
            }
        }
        return sb.toString()
    }

    /**
     * Extract chat messages specifically -- looks for message-like patterns
     * in the accessibility tree. Uses position heuristics to determine
     * sent vs received messages.
     */
    fun extractChatMessages(elements: List<ScreenElement>): List<ChatMessage> {
        val messages = mutableListOf<ChatMessage>()
        val textElements = elements.filter {
            it.text.isNotEmpty() && !it.isClickable && !it.isEditable
        }

        for (el in textElements) {
            if (el.text.length > 1) { // Skip single chars
                messages.add(
                    ChatMessage(
                        text = el.text,
                        bounds = el.bounds,
                        isOnRight = el.bounds.centerX() > 540 // rough heuristic for sent vs received
                    )
                )
            }
        }
        return messages
    }

    data class ChatMessage(
        val text: String,
        val bounds: Rect,
        val isOnRight: Boolean // true = likely sent by user
    )
}
