package com.yinu.app

import android.content.Context
import androidx.room.Room
import com.yinu.data.RecordRepository
import com.yinu.database.YinuDatabase
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
object LocalStorageModule {
    @Provides
    @Singleton
    fun database(@ApplicationContext context: Context): YinuDatabase =
        Room.databaseBuilder(context, YinuDatabase::class.java, "yinu.db").build()

    @Provides
    @Singleton
    fun records(@ApplicationContext context: Context, database: YinuDatabase): RecordRepository =
        RecordRepository(context, database)
}
