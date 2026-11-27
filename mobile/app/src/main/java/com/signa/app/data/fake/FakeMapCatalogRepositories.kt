package com.signa.app.data.fake

import com.signa.app.data.local.database.SignCatalogSnapshot
import com.signa.app.data.remote.backend.error.ApiException
import com.signa.app.data.remote.backend.dto.*
import com.signa.app.data.repository.*
import com.signa.app.domain.model.TrafficSign
import java.io.IOException
import java.time.Instant
import java.util.UUID
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

private const val PROHIBITORY = "PROHIBITORY"

class FakeSignCatalogRepository : SignCatalogRepository {
    private val sample = SignCatalogSnapshot(
        listOf(SignCategoryDto(PROHIBITORY, "Biển cấm", sortOrder = 1)),
        SignTypesDto(1, FakeData.signs.map {
            SignTypeDto(it.code, PROHIBITORY, it.name, it.meaning, it.speedLimitKmh, recognizable = true)
        })
    )
    override val snapshots = flowOf(sample)
    override suspend fun refresh() = Unit
    override suspend fun type(code: String) = sample.types.items.firstOrNull { it.code == code }
}

class FakeSignMapRepository(private val currentUser: () -> String? = { "sample-user" }) : SignMapRepository {
    private val mutex = Mutex()
    private val ratingsBySign = mutableMapOf<String, MutableMap<String, SignRating>>()
    private val reportsByUser = mutableMapOf<String, MutableList<MySignReportDto>>()
    private val updatedAt = Instant.now().toString()

    override suspend fun visible(viewport: SignViewport, categories: Set<String>, signTypeCodes: Set<String>) =
        VisibleSigns(FakeData.signs.filter { sign ->
            val point = requireNotNull(sign.position)
            val inLongitude = if (viewport.west < viewport.east) point.longitude in viewport.west..viewport.east
                else point.longitude >= viewport.west || point.longitude <= viewport.east
            inLongitude && point.latitude in viewport.south..viewport.north &&
                (categories.isEmpty() || PROHIBITORY in categories) &&
                (signTypeCodes.isEmpty() || sign.code in signTypeCodes)
        }.map { MapSignDto(it.id, it.code, PROHIBITORY, it.location(), status = "APPROVED", confidence = 0.85) })

    override suspend fun detail(id: String): SignDetailDto = mutex.withLock {
        val sign = requireSampleSign(id)
        val ratings = ratingsBySign[id].orEmpty()
        SignDetailDto(
            id, sign.code, PROHIBITORY, sign.location(), status = "APPROVED", confidence = 0.85,
            confirmCount = ratings.values.count { it == SignRating.CONFIRM },
            denyCount = ratings.values.count { it == SignRating.DENY },
            updatedAt = updatedAt, myRating = currentUser()?.let(ratings::get)
        )
    }

    override suspend fun rate(id: String, rating: SignRating) = mutex.withLock {
        requireSampleSign(id)
        ratingsBySign.getOrPut(id) { mutableMapOf() }[requireUser()] = rating
    }

    override suspend fun report(id: String, metadata: SignReportDto, photo: ReportPhoto?): SignReportReceiptDto =
        mutex.withLock {
            requireSampleSign(id)
            val reports = reportsByUser.getOrPut(requireUser()) { mutableListOf() }
            if (reports.any { it.signId == id && it.status == ReportStatus.PENDING }) {
                throw ApiException(409, null, IllegalStateException("Đã có báo cáo đang chờ"))
            }
            val report = MySignReportDto(UUID.randomUUID().toString(), id, metadata.reason, metadata.description,
                ReportStatus.PENDING, createdAt = Instant.now().toString())
            reports.add(0, report)
            SignReportReceiptDto(report.id, report.status)
        }

    override suspend fun myReports(page: Int) = mutex.withLock {
        val reports = reportsByUser[requireUser()].orEmpty()
        MySignReportsDto(reports.drop(page * SIGN_REPORT_PAGE_SIZE).take(SIGN_REPORT_PAGE_SIZE),
            page, SIGN_REPORT_PAGE_SIZE, reports.size.toLong())
    }

    private fun requireUser() = currentUser() ?: throw ApiException(401, null, IllegalStateException("Chưa đăng nhập"))

    private fun requireSampleSign(id: String) = FakeData.signs
        .firstOrNull { it.id == id } ?: throw IOException("Không tìm thấy biển báo")

    private fun TrafficSign.location(): SignLocationDto {
        val point = requireNotNull(position)
        return SignLocationDto(point.latitude, point.longitude)
    }
}
