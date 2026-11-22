package dev.sharptalons.obsdirector.core

import androidx.core.content.FileProvider

/**
 * Shares exported clip audio with other apps (res/xml/share_paths.xml). Kept apart from the
 * update provider, which may expose nothing but the downloaded update.
 */
class ShareProvider : FileProvider()
