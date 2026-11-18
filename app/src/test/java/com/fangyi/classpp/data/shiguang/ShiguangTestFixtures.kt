package com.fangyi.classpp.data.shiguang

import java.io.ByteArrayOutputStream

/** 拾光索引/脚本测试共用的 protobuf 手工编码与适配器构造 */
internal object ShiguangTestFixtures {

    const val SCRIPT_URL_PREFIX =
        "https://gitee.com/XingHeYuZhuan-gh/shiguang_warehouse/raw/main/resources/"

    fun indexBytes(
        protocolVersion: Int = 2,
        versionId: String = "TIME_TEST",
        schools: List<ByteArray> = listOf(
            schoolBytes(adapters = listOf(adapterBytes())),
        ),
        extraTopLevel: ByteArray = ByteArray(0),
    ): ByteArray = concat(
        int32Field(1, protocolVersion),
        stringField(2, versionId),
        *schools.map { messageField(3, it) }.toTypedArray(),
        extraTopLevel,
    )

    fun schoolBytes(
        id: String = "CQU",
        name: String = "重庆大学",
        initial: String = "C",
        resourceFolder: String = "CQU",
        adapters: List<ByteArray> = listOf(adapterBytes()),
    ): ByteArray = concat(
        stringField(1, id),
        stringField(2, name),
        stringField(3, initial),
        stringField(4, resourceFolder),
        *adapters.map { messageField(5, it) }.toTypedArray(),
    )

    fun adapterBytes(
        adapterId: String = "CQU",
        adapterName: String = "重庆大学教务",
        category: Int = 2,
        assetPath: String = "cqu.js",
        importUrl: String? = "https://my.cqu.edu.cn/",
        description: String = "适配重庆大学智慧教务系统",
        maintainer: String = "茵符草",
    ): ByteArray = concat(
        stringField(1, adapterId),
        stringField(2, adapterName),
        int32Field(3, category),
        stringField(4, assetPath),
        if (importUrl == null) ByteArray(0) else stringField(5, importUrl),
        stringField(6, description),
        stringField(7, maintainer),
    )

    fun validAdapter(
        schoolId: String = "CQU",
        schoolName: String = "重庆大学",
        resourceFolder: String = "CQU",
        adapterId: String = "CQU",
        assetPath: String = "cqu.js",
        importUrl: String = "https://my.cqu.edu.cn/",
        category: ShiguangAdapterCategory = ShiguangAdapterCategory.BACHELOR_AND_ASSOCIATE,
    ): ShiguangAdapter = ShiguangAdapter(
        schoolId = schoolId,
        schoolName = schoolName,
        initial = "C",
        resourceFolder = resourceFolder,
        adapterId = adapterId,
        adapterName = "重庆大学教务",
        category = category,
        assetPath = assetPath,
        importUrl = importUrl,
        description = "适配重庆大学智慧教务系统",
        maintainer = "茵符草",
    )

    // ---------- protobuf wire 编码 ----------

    fun int32Field(field: Int, value: Int): ByteArray =
        concat(tag(field, WIRE_VARINT), varint(value))

    fun stringField(field: Int, value: String): ByteArray =
        bytesField(field, value.toByteArray(Charsets.UTF_8))

    fun messageField(field: Int, body: ByteArray): ByteArray = bytesField(field, body)

    fun bytesField(field: Int, body: ByteArray): ByteArray =
        concat(tag(field, WIRE_LENGTH_DELIMITED), varint(body.size), body)

    fun tag(field: Int, wireType: Int): ByteArray = varint((field shl 3) or wireType)

    fun varint(value: Int): ByteArray {
        var current = value
        val out = ByteArrayOutputStream()
        while (true) {
            if (current and 0x7F.inv() == 0) {
                out.write(current)
                break
            }
            out.write((current and 0x7F) or 0x80)
            current = current ushr 7
        }
        return out.toByteArray()
    }

    fun concat(vararg parts: ByteArray): ByteArray {
        val out = ByteArrayOutputStream()
        parts.forEach { out.write(it) }
        return out.toByteArray()
    }

    private const val WIRE_VARINT = 0
    private const val WIRE_LENGTH_DELIMITED = 2
}
