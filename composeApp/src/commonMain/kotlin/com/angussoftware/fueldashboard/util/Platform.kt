package com.angussoftware.fueldashboard.util

/**
 * True on desktop (JVM), false on mobile (Android/iOS).
 * Used to gate desktop-only features like the ingestion manager.
 */
expect val isDesktopPlatform: Boolean
