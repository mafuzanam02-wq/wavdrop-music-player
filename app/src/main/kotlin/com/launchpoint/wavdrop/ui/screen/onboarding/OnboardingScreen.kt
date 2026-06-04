package com.launchpoint.wavdrop.ui.screen.onboarding

import androidx.compose.animation.Crossfade
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Backup
import androidx.compose.material.icons.filled.LibraryMusic
import androidx.compose.material.icons.filled.PrivacyTip
import androidx.compose.material.icons.filled.ViewList
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp

@Composable
fun OnboardingScreen(
    onComplete: () -> Unit,
) {
    var pageIndex by rememberSaveable { mutableIntStateOf(0) }
    val page = OnboardingPages[pageIndex]
    val isLastPage = pageIndex == OnboardingPages.lastIndex

    Scaffold { innerPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .padding(horizontal = 24.dp, vertical = 18.dp),
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.End,
            ) {
                TextButton(onClick = onComplete) {
                    Text("Skip")
                }
            }

            Box(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth(),
                contentAlignment = Alignment.Center,
            ) {
                Crossfade(
                    targetState = page,
                    label = "onboardingPage",
                ) { currentPage ->
                    OnboardingPageCard(page = currentPage)
                }
            }

            PageIndicators(
                selectedIndex = pageIndex,
                count = OnboardingPages.size,
                modifier = Modifier.fillMaxWidth(),
            )
            Spacer(Modifier.height(18.dp))
            Button(
                onClick = {
                    if (isLastPage) {
                        onComplete()
                    } else {
                        pageIndex += 1
                    }
                },
                modifier = Modifier.fillMaxWidth(),
                contentPadding = PaddingValues(vertical = 14.dp),
            ) {
                Text(if (isLastPage) "Get Started" else "Next")
            }
        }
    }
}

@Composable
private fun OnboardingPageCard(
    page: OnboardingPage,
    modifier: Modifier = Modifier,
) {
    Card(
        modifier = modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.48f),
        ),
    ) {
        Column(
            modifier = Modifier.padding(horizontal = 22.dp, vertical = 28.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Icon(
                imageVector = page.icon,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier.size(52.dp),
            )
            Spacer(Modifier.height(22.dp))
            Text(
                text = page.title,
                style = MaterialTheme.typography.headlineSmall,
                color = MaterialTheme.colorScheme.onSurface,
                textAlign = TextAlign.Center,
            )
            Spacer(Modifier.height(10.dp))
            Text(
                text = page.body,
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.72f),
                textAlign = TextAlign.Center,
            )
        }
    }
}

@Composable
private fun PageIndicators(
    selectedIndex: Int,
    count: Int,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier = modifier,
        horizontalArrangement = Arrangement.Center,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        repeat(count) { index ->
            val selected = index == selectedIndex
            Box(
                modifier = Modifier
                    .padding(horizontal = 4.dp)
                    .size(width = if (selected) 22.dp else 8.dp, height = 8.dp),
            ) {
                Card(
                    modifier = Modifier.fillMaxSize(),
                    colors = CardDefaults.cardColors(
                        containerColor = if (selected) {
                            MaterialTheme.colorScheme.primary
                        } else {
                            MaterialTheme.colorScheme.onSurface.copy(alpha = 0.22f)
                        },
                    ),
                ) {}
            }
        }
    }
}

private data class OnboardingPage(
    val title: String,
    val body: String,
    val icon: ImageVector,
)

private val OnboardingPages = listOf(
    OnboardingPage(
        title = "Welcome to Wavdrop",
        body = "A local music player for your own audio library.",
        icon = Icons.Default.LibraryMusic,
    ),
    OnboardingPage(
        title = "Your music stays yours",
        body = "Wavdrop works offline and keeps your library, playlists, lyrics, backups, and listening history on your device.",
        icon = Icons.Default.PrivacyTip,
    ),
    OnboardingPage(
        title = "Built for real libraries",
        body = "Browse songs, albums, artists, folders, playlists, smart collections, and accurate listening reports.",
        icon = Icons.Default.ViewList,
    ),
    OnboardingPage(
        title = "Ready when you are",
        body = "Scan your device, restore a Wavdrop backup, or import BlackPlayer stats when you need them.",
        icon = Icons.Default.Backup,
    ),
)
