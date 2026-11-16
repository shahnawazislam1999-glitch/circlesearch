package com.personal.circlesearch.clip

import androidx.core.content.FileProvider

/** Separate FileProvider so clipboard images never mix with Circle Search's own shared files. */
class ClipFileProvider : FileProvider()
