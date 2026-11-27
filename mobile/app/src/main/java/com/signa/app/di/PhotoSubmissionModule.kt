package com.signa.app.di

import android.content.Context
import androidx.room.Room
import com.signa.app.data.local.session.SessionManager
import com.signa.app.data.local.submissions.PhotoQueueDatabase
import com.signa.app.data.remote.backend.api.PhotoSubmissionApi
import com.signa.app.data.remote.backend.api.RecognitionApi
import com.signa.app.data.remote.backend.api.SubmissionOwner
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton
import okhttp3.OkHttpClient
import retrofit2.Retrofit
import java.io.IOException

@Module
@InstallIn(SingletonComponent::class)
object PhotoSubmissionModule {
    @Provides
    fun recognitionApi(retrofit: Retrofit): RecognitionApi = retrofit.create(RecognitionApi::class.java)

    @Provides
    fun photoSubmissionApi(retrofit: Retrofit, client: OkHttpClient, sessions: SessionManager): PhotoSubmissionApi {
        // SessionInterceptor supplies the Bearer token. Check it still belongs to the queued account.
        val guardedClient = client.newBuilder().addInterceptor { chain ->
            val request = chain.request()
            val owner = request.tag(SubmissionOwner::class.java)
            val session = sessions.current
            if (session == null || owner?.userId != session.user.id ||
                request.header("Authorization") != "Bearer ${session.id}"
            ) throw IOException("Phiên gửi đề xuất đã thay đổi")
            chain.proceed(request)
        }.build()
        return retrofit.newBuilder().client(guardedClient).build().create(PhotoSubmissionApi::class.java)
    }

    @Provides
    @Singleton
    fun photoQueueDatabase(@ApplicationContext context: Context): PhotoQueueDatabase =
        Room.databaseBuilder(context, PhotoQueueDatabase::class.java, "signa-photo-queue.db").build()
}
