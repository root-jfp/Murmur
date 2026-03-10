package com.murmur.reader.di

import android.content.Context
import androidx.room.Room
import com.murmur.reader.data.local.BookmarkDao
import com.murmur.reader.data.local.CachedChunkDao
import com.murmur.reader.data.local.MurmurDatabase
import com.murmur.reader.data.local.ReadingProgressDao
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import okhttp3.OkHttpClient
import java.util.concurrent.TimeUnit
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
object AppModule {

    @Provides
    @Singleton
    fun provideOkHttpClient(): OkHttpClient {
        return OkHttpClient.Builder()
            .connectTimeout(15, TimeUnit.SECONDS)
            .readTimeout(30, TimeUnit.SECONDS)
            .writeTimeout(15, TimeUnit.SECONDS)
            .retryOnConnectionFailure(true)
            .build()
    }

    @Provides
    @Singleton
    fun provideMurmurDatabase(@ApplicationContext context: Context): MurmurDatabase {
        return Room.databaseBuilder(
            context,
            MurmurDatabase::class.java,
            "murmur.db"
        ).addMigrations(MurmurDatabase.MIGRATION_1_2)
            .build()
    }

    @Provides
    fun provideReadingProgressDao(db: MurmurDatabase): ReadingProgressDao = db.readingProgressDao()

    @Provides
    fun provideBookmarkDao(db: MurmurDatabase): BookmarkDao = db.bookmarkDao()

    @Provides
    fun provideCachedChunkDao(db: MurmurDatabase): CachedChunkDao = db.cachedChunkDao()
}
