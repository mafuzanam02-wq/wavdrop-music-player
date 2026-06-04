package com.launchpoint.wavdrop.ui.screen.settings

import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.launchpoint.wavdrop.BuildConfig

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsAboutScreen(
    onNavigateBack: () -> Unit,
) {
    var showFormatsDialog        by remember { mutableStateOf(false) }
    var showPrivacyPolicyDialog  by remember { mutableStateOf(false) }
    var showDisclaimerDialog     by remember { mutableStateOf(false) }
    var showOpenSourceDialog     by remember { mutableStateOf(false) }
    val context = LocalContext.current

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("About") },
                navigationIcon = {
                    IconButton(onClick = onNavigateBack) {
                        Icon(
                            imageVector        = Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = "Back",
                        )
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor    = MaterialTheme.colorScheme.surface,
                    titleContentColor = MaterialTheme.colorScheme.onSurface,
                ),
            )
        },
    ) { innerPadding ->
        LazyColumn(
            modifier = Modifier
                .padding(innerPadding)
                .fillMaxSize(),
        ) {
            item { SectionHeader("App Info") }
            item { AppIdentityCard() }
            item {
                AboutInfoRow(label = "Version name", value = BuildConfig.VERSION_NAME)
                AboutInfoRow(label = "Version code", value = BuildConfig.VERSION_CODE.toString())
                AboutInfoRow(label = "Package",      value = WavdropAbout.PACKAGE_NAME)
            }
            item { SectionDivider() }
            item {
                ClickableSettingsRow(
                    title    = "Powered by LaunchPoint Digital",
                    subtitle = "Websites, apps and digital tools",
                    onClick  = { context.openWebsite(WavdropAbout.WEBSITE_URL) },
                )
            }
            item {
                ClickableSettingsRow(
                    title    = "Contact Support",
                    subtitle = WavdropAbout.CONTACT_EMAIL,
                    onClick  = {
                        context.openSupportEmail(
                            email   = WavdropAbout.CONTACT_EMAIL,
                            subject = WavdropAbout.SUPPORT_SUBJECT,
                        )
                    },
                )
            }
            item { SectionDivider() }
            item {
                ClickableSettingsRow(
                    title    = "Privacy Policy",
                    subtitle = "Offline-first data and permissions",
                    onClick  = { showPrivacyPolicyDialog = true },
                )
            }
            item {
                ClickableSettingsRow(
                    title    = "Disclaimer",
                    subtitle = "Independence, imports, formats and content",
                    onClick  = { showDisclaimerDialog = true },
                )
            }
            item {
                ClickableSettingsRow(
                    title    = "Open Source Licenses",
                    subtitle = "Libraries and technologies used by Wavdrop",
                    onClick  = { showOpenSourceDialog = true },
                )
            }
            item {
                ClickableSettingsRow(
                    title    = "Supported Audio Formats",
                    subtitle = "MP3, AAC, FLAC, OGG, Opus, WAV and more",
                    onClick  = { showFormatsDialog = true },
                )
            }
            item { CopyrightRow() }
            item { Spacer(Modifier.height(24.dp)) }
        }
    }

    if (showFormatsDialog) {
        AudioFormatsDialog(onDismiss = { showFormatsDialog = false })
    }
    if (showPrivacyPolicyDialog) {
        LegalInfoDialog(
            title      = "Privacy Policy",
            paragraphs = WavdropAbout.PRIVACY_POLICY,
            onDismiss  = { showPrivacyPolicyDialog = false },
        )
    }
    if (showDisclaimerDialog) {
        LegalInfoDialog(
            title      = "Disclaimer",
            paragraphs = WavdropAbout.DISCLAIMER,
            onDismiss  = { showDisclaimerDialog = false },
        )
    }
    if (showOpenSourceDialog) {
        LegalInfoDialog(
            title      = "Open Source Licenses",
            paragraphs = WavdropAbout.OPEN_SOURCE_LICENSES,
            onDismiss  = { showOpenSourceDialog = false },
        )
    }
}

@Composable
private fun AppIdentityCard(modifier: Modifier = Modifier) {
    Card(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 8.dp),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.45f),
        ),
    ) {
        Column(modifier = Modifier.padding(horizontal = 16.dp, vertical = 14.dp)) {
            Text(
                text  = WavdropAbout.APP_NAME,
                style = MaterialTheme.typography.titleLarge,
                color = MaterialTheme.colorScheme.onSurface,
            )
            Spacer(Modifier.height(2.dp))
            Text(
                text  = WavdropAbout.PRODUCT_NAME,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.72f),
            )
            Spacer(Modifier.height(12.dp))
            AboutInfoRow(label = "Package name", value = WavdropAbout.PACKAGE_NAME)
            AboutInfoRow(label = "Version name", value = BuildConfig.VERSION_NAME)
            AboutInfoRow(label = "Version code", value = BuildConfig.VERSION_CODE.toString())
        }
    }
}

@Composable
private fun CopyrightRow(modifier: Modifier = Modifier) {
    Text(
        text     = WavdropAbout.COPYRIGHT,
        style    = MaterialTheme.typography.bodySmall,
        color    = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f),
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 14.dp),
    )
}

@Composable
private fun LegalInfoDialog(
    title: String,
    paragraphs: List<String>,
    onDismiss: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title            = { Text(title) },
        text             = {
            Column(modifier = Modifier.verticalScroll(rememberScrollState())) {
                paragraphs.forEachIndexed { index, paragraph ->
                    if (index > 0) Spacer(Modifier.height(10.dp))
                    Text(
                        text  = paragraph,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.78f),
                    )
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) { Text("Close") }
        },
    )
}

@Composable
private fun AudioFormatsDialog(onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title            = { Text("Supported Audio Formats") },
        text             = {
            Column(modifier = Modifier.verticalScroll(rememberScrollState())) {
                Text(
                    text  = "Wavdrop supports formats that Android MediaStore can index " +
                            "and Media3/ExoPlayer can play on your device.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.7f),
                )
                Spacer(Modifier.height(14.dp))
                FormatGroup(
                    title   = "Reliably supported",
                    formats = "MP3 · AAC / M4A · FLAC · OGG Vorbis · Opus · WAV · AMR · MIDI",
                )
                Spacer(Modifier.height(12.dp))
                FormatGroup(
                    title   = "Device-dependent",
                    formats = "ALAC · WMA · AC-3 · E-AC-3 · DTS · High-bit-depth WAV",
                )
                Spacer(Modifier.height(12.dp))
                FormatGroup(
                    title   = "Not currently supported",
                    formats = "APE · AIFF · WavPack · Musepack · TTA · DSD / DSF / DFF · RealAudio",
                )
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) { Text("Close") }
        },
    )
}

@Composable
private fun FormatGroup(
    title: String,
    formats: String,
    modifier: Modifier = Modifier,
) {
    Column(modifier = modifier) {
        Text(
            text  = title,
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.primary,
        )
        Spacer(Modifier.height(3.dp))
        Text(
            text  = formats,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.85f),
        )
    }
}

private fun Context.openWebsite(url: String) {
    runCatching { startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url))) }
}

private fun Context.openSupportEmail(email: String, subject: String) {
    val intent = Intent(Intent.ACTION_SENDTO).apply {
        data = Uri.parse("mailto:")
        putExtra(Intent.EXTRA_EMAIL, arrayOf(email))
        putExtra(Intent.EXTRA_SUBJECT, subject)
    }
    runCatching { startActivity(intent) }
}

private object WavdropAbout {
    const val APP_NAME      = "Wavdrop"
    const val PRODUCT_NAME  = "Wavdrop Music Player"
    const val PACKAGE_NAME  = "com.launchpoint.wavdrop"
    const val WEBSITE_URL   = "https://launchpointdigital.co.za"
    const val CONTACT_EMAIL = "hello.launchpointdigital@gmail.com"
    const val SUPPORT_SUBJECT = "Wavdrop Music Player Support"
    const val COPYRIGHT     = "© 2026 LaunchPoint Digital. All rights reserved."

    val PRIVACY_POLICY = listOf(
        "Wavdrop is an offline-first music player. It does not require an account, does not upload your music files, and does not sell user data.",
        "Music library data, playlists, lyrics overrides, preferences, backups, statistics, and listening history are stored locally on your device unless you manually export or import backup files.",
        "Android permissions are used only for music/library access and playback-related functionality.",
        "If optional cloud backup or sync is added in the future, it must remain opt-in.",
    )

    val DISCLAIMER = listOf(
        "Wavdrop is an independent music player and is not affiliated with BlackPlayer, ExoPlayer, Android, Google, Samsung, or any music service.",
        "BlackPlayer import exists only to help users migrate their own listening statistics from their own exported files.",
        "Format support depends partly on Android device codecs.",
        "Users are responsible for their own music files and backups.",
        "Wavdrop does not provide, host, sell, or stream music content.",
    )

    val OPEN_SOURCE_LICENSES = listOf(
        "Wavdrop uses open-source Android libraries and technologies.",
        "Key technologies include Kotlin, Jetpack Compose, Media3 / ExoPlayer, Room, Hilt, and Coil.",
        "Detailed license information will be added before a full public release. This screen does not claim exact license terms unless they have been verified in project files.",
    )
}
