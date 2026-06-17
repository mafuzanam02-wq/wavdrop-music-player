package com.launchpoint.wavdrop.ui.screen.onboarding

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
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowLeft
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.automirrored.filled.ViewList
import androidx.compose.material.icons.filled.Backup
import androidx.compose.material.icons.filled.LibraryMusic
import androidx.compose.material.icons.filled.PhoneAndroid
import androidx.compose.material.icons.filled.PrivacyTip
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.launch

@Composable
fun OnboardingScreen(
    onComplete: () -> Unit,
) {
    val pagerState     = rememberPagerState(pageCount = { OnboardingPages.size })
    val coroutineScope = rememberCoroutineScope()
    val isLastPage     = pagerState.currentPage == OnboardingPages.lastIndex

    Scaffold { innerPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .padding(horizontal = 24.dp, vertical = 18.dp),
        ) {
            Row(
                modifier              = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.End,
            ) {
                TextButton(onClick = onComplete) {
                    Text("Skip")
                }
            }

            HorizontalPager(
                state    = pagerState,
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth(),
            ) { page ->
                Box(
                    modifier         = Modifier.fillMaxSize(),
                    contentAlignment = Alignment.Center,
                ) {
                    OnboardingPageCard(page = OnboardingPages[page])
                }
            }

            Row(
                modifier          = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                IconButton(
                    onClick = {
                        coroutineScope.launch {
                            val target = if (pagerState.currentPage == 0) {
                                OnboardingPages.lastIndex
                            } else {
                                pagerState.currentPage - 1
                            }
                            pagerState.animateScrollToPage(target)
                        }
                    },
                ) {
                    Icon(
                        imageVector        = Icons.AutoMirrored.Filled.KeyboardArrowLeft,
                        contentDescription = "Previous onboarding page",
                        tint               = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f),
                    )
                }

                PageIndicators(
                    selectedIndex = pagerState.currentPage,
                    count         = OnboardingPages.size,
                )

                IconButton(
                    onClick = {
                        coroutineScope.launch {
                            val target = if (pagerState.currentPage == OnboardingPages.lastIndex) {
                                0
                            } else {
                                pagerState.currentPage + 1
                            }
                            pagerState.animateScrollToPage(target)
                        }
                    },
                ) {
                    Icon(
                        imageVector        = Icons.AutoMirrored.Filled.KeyboardArrowRight,
                        contentDescription = "Next onboarding page",
                        tint               = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f),
                    )
                }
            }

            Spacer(Modifier.height(14.dp))
            Button(
                onClick = {
                    if (isLastPage) {
                        onComplete()
                    } else {
                        coroutineScope.launch {
                            pagerState.animateScrollToPage(pagerState.currentPage + 1)
                        }
                    }
                },
                modifier       = Modifier.fillMaxWidth(),
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
        colors   = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant,
        ),
    ) {
        Column(
            modifier            = Modifier.padding(horizontal = 22.dp, vertical = 28.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Icon(
                imageVector        = page.icon,
                contentDescription = null,
                tint               = MaterialTheme.colorScheme.primary,
                modifier           = Modifier.size(52.dp),
            )
            Spacer(Modifier.height(22.dp))
            Text(
                text      = page.title,
                style     = MaterialTheme.typography.headlineSmall,
                color     = MaterialTheme.colorScheme.onSurface,
                textAlign = TextAlign.Center,
            )
            Spacer(Modifier.height(10.dp))
            Text(
                text      = page.body,
                style     = MaterialTheme.typography.bodyLarge,
                color     = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.72f),
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
        modifier              = modifier,
        horizontalArrangement = Arrangement.Center,
        verticalAlignment     = Alignment.CenterVertically,
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
                    colors   = CardDefaults.cardColors(
                        containerColor = if (selected) {
                            MaterialTheme.colorScheme.primary
                        } else {
                            MaterialTheme.colorScheme.onSurface.copy(alpha = 0.30f)
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
        body  = "A local music player for your own audio library.",
        icon  = Icons.Default.LibraryMusic,
    ),
    OnboardingPage(
        title = "Your music stays yours",
        body  = "Wavdrop works offline and keeps your library, playlists, lyrics, backups, and listening history on your device.",
        icon  = Icons.Default.PrivacyTip,
    ),
    OnboardingPage(
        title = "Built for real libraries",
        body  = "Browse songs, albums, artists, folders, playlists, smart collections, and accurate listening reports.",
        icon  = Icons.AutoMirrored.Filled.ViewList,
    ),
    OnboardingPage(
        title = "Ready when you are",
        body  = "Scan your device, restore a Wavdrop backup, or import BlackPlayer stats when you need them.",
        icon  = Icons.Default.Backup,
    ),
    OnboardingPage(
        title = "Allow access to find your music",
        body  = "Wavdrop reads audio files already saved on this device. Tap Allow when prompted — your music never leaves your phone. No account, no cloud, no ads.",
        icon  = Icons.Default.PhoneAndroid,
    ),
)
