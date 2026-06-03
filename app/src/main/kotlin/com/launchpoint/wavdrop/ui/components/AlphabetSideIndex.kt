package com.launchpoint.wavdrop.ui.components

import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp

/**
 * Vertical A-Z fast-scroll index shown on the trailing edge of long lists.
 *
 * @param activeLetter Letter that should be visually highlighted (matches the first
 *                     visible section in the adjacent list). Pass null when unknown.
 * @param onLetterSelected Called on tap AND during drag as the finger moves over letters.
 */
@Composable
fun AlphabetSideIndex(
    activeLetter: Char? = null,
    onLetterSelected: (Char) -> Unit,
    modifier: Modifier = Modifier,
) {
    val letters = AlphabetLetters

    Column(
        modifier = modifier
            .width(28.dp)
            .fillMaxHeight()
            .padding(vertical = 6.dp)
            .pointerInput(Unit) {
                fun letterAt(y: Float): Char {
                    val index = ((y / size.height.toFloat()) * letters.size)
                        .toInt()
                        .coerceIn(0, letters.lastIndex)
                    return letters[index]
                }
                awaitEachGesture {
                    val down = awaitFirstDown(requireUnconsumed = false)
                    var lastLetter = letterAt(down.position.y)
                    onLetterSelected(lastLetter)
                    do {
                        val event = awaitPointerEvent()
                        val change = event.changes.firstOrNull() ?: break
                        if (!change.pressed) break
                        val newLetter = letterAt(change.position.y)
                        if (newLetter != lastLetter) {
                            lastLetter = newLetter
                            onLetterSelected(newLetter)
                        }
                    } while (true)
                }
            },
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.SpaceEvenly,
    ) {
        letters.forEach { letter ->
            val isActive = letter == activeLetter
            Text(
                text = letter.toString(),
                style = MaterialTheme.typography.labelSmall,
                color = if (isActive) MaterialTheme.colorScheme.primary
                        else MaterialTheme.colorScheme.onSurface.copy(alpha = 0.40f),
                fontWeight = if (isActive) FontWeight.Bold else FontWeight.Normal,
                textAlign = TextAlign.Center,
                modifier = Modifier
                    .width(28.dp)
                    .heightIn(min = 18.dp),
            )
        }
    }
}

private val AlphabetLetters = listOf('#') + ('A'..'Z').toList()
