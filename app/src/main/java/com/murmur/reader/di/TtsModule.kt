package com.murmur.reader.di

import com.murmur.reader.document.EpubParser
import com.murmur.reader.document.HtmlParser
import com.murmur.reader.document.PdfParser
import com.murmur.reader.document.PlainTextParser
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
object TtsModule {
    // EdgeTtsClient, TtsManager, AudioPlayer, VoiceRepository, TextChunker,
    // NetworkUtil, TextSanitizer are all @Singleton with @Inject constructors
    // — Hilt wires them automatically. No manual @Provides needed here.

    // Document parsers also use @Inject constructors, so they're auto-wired.
    // This module is kept as a placeholder for any future manual bindings.
}
