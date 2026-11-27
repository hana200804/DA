package com.signa.app.di

import com.signa.app.BuildConfig
import com.signa.app.data.remote.backend.client.ApiClientFactory
import com.signa.app.data.remote.backend.client.ApiConfig
import com.signa.app.data.remote.backend.api.AuthApi
import com.signa.app.data.remote.backend.interceptor.SessionInterceptor
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton
import kotlinx.serialization.json.Json
import okhttp3.OkHttpClient
import retrofit2.Retrofit

@Module
@InstallIn(SingletonComponent::class)
object NetworkModule {
    @Provides @Singleton fun provideJson(): Json = Json { ignoreUnknownKeys = true }
    @Provides @Singleton fun provideConfig(): ApiConfig = ApiConfig(BuildConfig.API_BASE_URL)
    @Provides @Singleton fun provideClient(interceptor: SessionInterceptor): OkHttpClient = ApiClientFactory.client(interceptor)
    @Provides @Singleton fun provideRetrofit(config: ApiConfig, client: OkHttpClient, json: Json): Retrofit = ApiClientFactory.retrofit(config, client, json)
    @Provides @Singleton fun provideAuthApi(retrofit: Retrofit): AuthApi = retrofit.create(AuthApi::class.java)
}
