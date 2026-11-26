package com.signa.app.di

import android.content.Context
import androidx.room.Room
import com.signa.app.BuildConfig
import com.signa.app.data.fake.FakeSignCatalogRepository
import com.signa.app.data.fake.FakeSignMapRepository
import com.signa.app.data.local.database.RoomSignCatalogStore
import com.signa.app.data.local.database.SignCatalogDatabase
import com.signa.app.data.local.database.SignCatalogSnapshot
import com.signa.app.data.local.session.SessionManager
import com.signa.app.data.remote.ApiConfig
import com.signa.app.data.remote.api.SignaMapApi
import com.signa.app.data.repository.RemoteSignCatalogRepository
import com.signa.app.data.repository.RemoteSignMapRepository
import com.signa.app.data.repository.SignCatalogRepository
import com.signa.app.data.repository.SignMapRepository
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton
import kotlinx.serialization.json.Json
import retrofit2.Retrofit

@Module
@InstallIn(SingletonComponent::class)
object SignMapModule {
    @Provides
    @Singleton
    fun provideSignMapApi(retrofit: Retrofit): SignaMapApi = retrofit.create(SignaMapApi::class.java)

    @Provides
    @Singleton
    fun provideSignCatalog(
        @ApplicationContext context: Context,
        api: SignaMapApi,
        config: ApiConfig,
        json: Json
    ): SignCatalogRepository {
        if (BuildConfig.USE_FAKE_BACKEND) return FakeSignCatalogRepository()
        val database = Room.databaseBuilder(context, SignCatalogDatabase::class.java, "sign-catalog.db").build()
        val bundled = context.assets.open("sign-catalog-v1.json").bufferedReader().use {
            json.decodeFromString<SignCatalogSnapshot>(it.readText())
        }
        val store = RoomSignCatalogStore(database.catalog(), json, config.baseUrl.toString(), bundled)
        return RemoteSignCatalogRepository(api, store, config, json)
    }

    @Provides
    @Singleton
    fun provideSignMap(api: SignaMapApi, json: Json, sessions: SessionManager): SignMapRepository =
        if (BuildConfig.USE_FAKE_BACKEND) FakeSignMapRepository { sessions.current?.user?.id }
        else RemoteSignMapRepository(api, json)
}
