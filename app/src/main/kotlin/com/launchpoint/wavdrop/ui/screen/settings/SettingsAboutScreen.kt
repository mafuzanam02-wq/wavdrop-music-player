package com.launchpoint.wavdrop.ui.screen.settings

import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
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
    onDiagnosticsClick: () -> Unit,
) {
    var showFormatsDialog        by remember { mutableStateOf(false) }
    var showPrivacyPolicyDialog  by remember { mutableStateOf(false) }
    var showTermsDialog          by remember { mutableStateOf(false) }
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
            contentPadding = PaddingValues(bottom = 24.dp),
        ) {
            item { SectionHeader("Wavdrop") }
            item { AppIdentityCard() }
            item { SectionHeader("Privacy") }
            item {
                AboutPromiseCard(
                    title = "Offline-first by design",
                    body = "Wavdrop plays music already on your device. It does not require an account, upload your songs, or sell user data.",
                )
            }
            item {
                AboutPromiseCard(
                    title = "Your library stays yours",
                    body = "Library data, playlists, lyrics overrides, preferences, backups, statistics, and listening history stay local unless you manually export or import backup files.",
                )
            }
            item { SectionHeader("Built By") }
            item {
                ClickableSettingsRow(
                    title    = "LaunchPoint Digital",
                    subtitle = "Websites, apps and digital tools",
                    onClick  = { context.openWebsite(WavdropAbout.WEBSITE_URL) },
                )
            }
            item { SectionDivider() }
            item { SectionHeader("Support") }
            item {
                ClickableSettingsRow(
                    title    = "Contact Support",
                    subtitle = "${WavdropAbout.CONTACT_EMAIL} · Include your Wavdrop version and Android device.",
                    onClick  = {
                        context.openSupportEmail(
                            email   = WavdropAbout.CONTACT_EMAIL,
                            subject = WavdropAbout.SUPPORT_SUBJECT,
                        )
                    },
                )
            }
            item { SectionDivider() }
            item { SectionHeader("Tools") }
            item {
                ClickableSettingsRow(
                    title    = "Supported Audio Formats",
                    subtitle = "Android and Media3 playback support by device",
                    onClick  = { showFormatsDialog = true },
                )
            }
            item {
                ClickableSettingsRow(
                    title    = "Diagnostics",
                    subtitle = "Read-only tester snapshot with no private library details",
                    onClick  = onDiagnosticsClick,
                )
            }
            item { SectionDivider() }
            item { SectionHeader("Legal") }
            item {
                ClickableSettingsRow(
                    title    = "Privacy Policy",
                    subtitle = "Offline-first data, local storage, and permissions",
                    onClick  = { showPrivacyPolicyDialog = true },
                )
            }
            item {
                ClickableSettingsRow(
                    title    = "Terms of Use",
                    subtitle = "Your responsibilities and app use guidelines",
                    onClick  = { showTermsDialog = true },
                )
            }
            item {
                ClickableSettingsRow(
                    title    = "Disclaimer",
                    subtitle = "Independent player, imports, formats, and user content",
                    onClick  = { showDisclaimerDialog = true },
                )
            }
            item {
                ClickableSettingsRow(
                    title    = "Open Source Licenses",
                    subtitle = "Open-source Android technologies used by Wavdrop",
                    onClick  = { showOpenSourceDialog = true },
                )
            }
            item { CopyrightRow() }
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
    if (showTermsDialog) {
        LegalInfoDialog(
            title      = "Terms of Use",
            paragraphs = WavdropAbout.TERMS_OF_USE,
            onDismiss  = { showTermsDialog = false },
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
            containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.50f),
        ),
    ) {
        Column(modifier = Modifier.padding(horizontal = 16.dp, vertical = 14.dp)) {
            Text(
                text  = WavdropAbout.PRODUCT_NAME,
                style = MaterialTheme.typography.titleLarge,
                color = MaterialTheme.colorScheme.onSurface,
            )
            Spacer(Modifier.height(2.dp))
            Text(
                text  = "A local music player for your own audio library.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.72f),
            )
            Spacer(Modifier.height(12.dp))
            CompactInfoLine(label = "App", value = WavdropAbout.APP_NAME)
            CompactInfoLine(label = "Version", value = BuildConfig.VERSION_NAME)
            CompactInfoLine(label = "Build", value = BuildConfig.VERSION_CODE.toString())
            CompactInfoLine(label = "Package", value = WavdropAbout.PACKAGE_NAME)
        }
    }
}

@Composable
private fun AboutPromiseCard(
    title: String,
    body: String,
    modifier: Modifier = Modifier,
) {
    Card(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 6.dp),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.primary.copy(alpha = 0.08f),
        ),
    ) {
        Column(modifier = Modifier.padding(horizontal = 16.dp, vertical = 14.dp)) {
            Text(
                text = title,
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.onSurface,
            )
            Spacer(Modifier.height(4.dp))
            Text(
                text = body,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.72f),
            )
        }
    }
}

@Composable
private fun CompactInfoLine(
    label: String,
    value: String,
    modifier: Modifier = Modifier,
) {
    Column(modifier = modifier.fillMaxWidth().padding(vertical = 3.dp)) {
        Text(
            text = label,
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.55f),
        )
        Text(
            text = value,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurface,
        )
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
                    formats = "MP3, AAC / M4A, FLAC, OGG Vorbis, Opus, WAV, AMR, MIDI",
                )
                Spacer(Modifier.height(12.dp))
                FormatGroup(
                    title   = "Device-dependent",
                    formats = "ALAC, WMA, AC-3, E-AC-3, DTS, high-bit-depth WAV",
                )
                Spacer(Modifier.height(12.dp))
                FormatGroup(
                    title   = "Not currently supported",
                    formats = "APE, AIFF, WavPack, Musepack, TTA, DSD / DSF / DFF, RealAudio",
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
    const val APP_NAME        = "Wavdrop"
    const val PRODUCT_NAME    = "Wavdrop Music Player"
    const val PACKAGE_NAME    = "com.launchpoint.wavdrop"
    const val WEBSITE_URL     = "https://launchpointdigital.co.za"
    const val CONTACT_EMAIL   = "hello.launchpointdigital@gmail.com"
    const val SUPPORT_SUBJECT = "Wavdrop Music Player Support"
    const val COPYRIGHT       = "© 2026 LaunchPoint Digital. All rights reserved."

    val PRIVACY_POLICY = listOf(
        "Wavdrop is an offline-first music player. It does not require an account, does not upload your music or data to any server, and does not sell or share your data with advertisers or third parties.",
        "Data stored locally on your device:\n" +
            "· Your music library: song titles, artists, albums, and file references read from your device's media library\n" +
            "· Play counts, skip counts, favorite status, and total listening time per track\n" +
            "· Per-play and per-skip event records with timestamps, used for statistics, monthly reports, and Wrapped\n" +
            "· Playlists and their track order\n" +
            "· Custom lyrics you add manually inside the app\n" +
            "· App settings and preferences, such as startup screen, scan folders, theme, icon choice, and compact mode\n\n" +
            "None of this data is transmitted to Wavdrop, LaunchPoint Digital, or any third party.",
        "Android permissions:\n" +
            "· READ_EXTERNAL_STORAGE on Android 8–12 or READ_MEDIA_AUDIO on Android 13+ is used to find and play audio files on your device\n" +
            "· FOREGROUND_SERVICE and FOREGROUND_SERVICE_MEDIA_PLAYBACK are used to keep music playing when the screen is off\n\n" +
            "Wavdrop does not request internet, location, contacts, camera, microphone, advertising ID, or any other unnecessary permissions.",
        "Backup and export:\n" +
            "The Export Wavdrop Data feature saves a JSON file to a location you choose, such as local storage or a cloud service you control. This file may contain your library metadata, statistics, playlists, custom lyrics, app preferences, and listening history. Wavdrop does not upload this file. You are responsible for protecting your backup because it may contain personal listening data.",
        "Share:\n" +
            "When you use the Share feature on a track, Wavdrop passes a content link for that audio file to the Android share sheet and to the app you choose. Wavdrop does not transmit any data itself and has no control over how the receiving app handles the shared file.",
        "Delete from device:\n" +
            "If you delete a track using Delete from device, the audio file is permanently removed from your device. Wavdrop may retain listening statistics and history records associated with that deleted track so that reports, Wrapped summaries, and historical listening data remain accurate. The deleted audio file is not recoverable through Wavdrop.",
        "BlackPlayer import:\n" +
            "The BlackPlayer import feature reads a .bpstat file from your device and writes matched play and skip counts into Wavdrop's local database. No data is sent anywhere during this process.",
        "Third-party services:\n" +
            "Wavdrop does not use advertising SDKs, analytics services, crash-reporting tools, or third-party tracking. External links, such as the LaunchPoint Digital website and support email, open in your device's browser or email app. Wavdrop itself makes no network requests.",
        "Contact:\n" +
            "Questions about this policy: hello.launchpointdigital@gmail.com",
    )

    val TERMS_OF_USE = listOf(
        "By using Wavdrop, you agree to the following terms. If you do not agree, please uninstall the app.",
        "Use of the app:\n" +
            "Wavdrop is designed for managing and playing audio files stored on your device.",
        "Your music files and rights:\n" +
            "You are responsible for ensuring you have the right to play, back up, and share any audio files you use with Wavdrop. Wavdrop does not verify music ownership or licensing.",
        "Delete from device:\n" +
            "Delete from device permanently removes audio files from your device. This action cannot be undone. Android does not provide a recycle bin for shared media storage. Use this feature with care.",
        "Backup files:\n" +
            "Backup files you export may contain your listening statistics, playlist names, custom lyrics, app preferences, and other personal library data in plain JSON format. Store them securely and do not share them with people you do not trust.",
        "Sharing:\n" +
            "When you share a track, you are responsible for what you share and with whom. Wavdrop opens the Android share sheet; it is not responsible for how the receiving app handles the shared file.",
        "Format support:\n" +
            "Audio format support depends partly on your Android device and its codecs. Wavdrop does not guarantee playback of all audio file types on all devices.",
        "BlackPlayer import:\n" +
            "Only import .bpstat files you exported from your own BlackPlayer installation. Do not attempt to import files you did not personally create.",
        "Changes to these terms:\n" +
            "These terms may be updated as new features are added. Continued use of Wavdrop after an update means you accept the revised terms.",
    )

    val DISCLAIMER = listOf(
        "Wavdrop is an independent music player. It is not affiliated with BlackPlayer or Immortal Soft, ExoPlayer, Kotlin, Jetpack Compose, Android, Google, Samsung, WhatsApp, Meta, or any music streaming service.",
        "BlackPlayer import exists only to help you migrate your own listening statistics from .bpstat files you exported from your own BlackPlayer installation. Wavdrop is not affiliated with Immortal Soft or the BlackPlayer product.",
        "Audio format support depends partly on your Android device's media codecs. Not all file types are guaranteed to work on all devices.",
        "You are responsible for your own music files, listening data, and backup files, and for ensuring you have appropriate rights to play, back up, or share any audio content you use with Wavdrop. Wavdrop does not verify music ownership or licensing.",
        "Wavdrop does not provide, host, sell, or stream music content.",
        "The Delete from device feature permanently removes audio files from your device and cannot be undone. Android does not provide a recycle bin for shared media storage. Wavdrop cannot recover deleted files.",
        "When you share a track using the Share feature, Wavdrop provides a content link to the app you choose. Wavdrop is not responsible for how the receiving app handles the shared file.",
        "Wavdrop statistics, reports, and Wrapped are computed from listening activity recorded locally on your device. History begins from when you started using Wavdrop; prior listening activity is not available unless imported via BlackPlayer import or a Wavdrop backup.",
    )

    val OPEN_SOURCE_LICENSES = listOf(
        "Wavdrop is built with open-source libraries. These libraries are used under the terms of their respective licenses.",
        "Apache License 2.0:\n" +
            "· Kotlin\n" +
            "· Jetpack Compose and Material3\n" +
            "· AndroidX Core, Lifecycle, Activity, and Navigation\n" +
            "· Room\n" +
            "· DataStore\n" +
            "· Media3 / ExoPlayer\n" +
            "· Hilt / Dagger\n" +
            "· Coil",
        "Apache License 2.0 permits use, modification, and distribution with attribution. Each library retains its original copyright and license notices. No warranty is provided by the library authors.",
        "For full copyright notices and license texts, visit each library's official repository.",
    )
}
