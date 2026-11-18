package com.fangyi.classpp.data.shiguang

/**
 * 拾光仓库索引（`school_index.pb`，proto v2）的最小只读解析。
 *
 * 上游 proto（`shiguang_warehouse/proto/school_index.proto`）：
 * ```
 * message SchoolIndex { int32 protocol_version = 1; string version_id = 2; repeated School schools = 3; }
 * message School {
 *     string id = 1; string name = 2; string initial = 3; string resource_folder = 4;
 *     repeated Adapter adapters = 5;
 * }
 * message Adapter {
 *     string adapter_id = 1; string adapter_name = 2; AdapterCategory category = 3;
 *     string asset_js_path = 4; optional string import_url = 5;
 *     string description = 6; string maintainer = 7;
 * }
 * enum AdapterCategory { ADAPTER_CATEGORY_UNKNOWN = 0; GENERAL_TOOL = 1;
 *                        BACHELOR_AND_ASSOCIATE = 2; POSTGRADUATE = 3; }
 * ```
 *
 * 只解析本功能需要的字段，未知字段按 wire type 跳过。索引由上游 CI 生成并发布在
 * `index-pb-release` 分支，`protocol_version` 是兼容闸门：结构大改时上游会递增，
 * 旧版 app 应拒绝解析而不是按旧字段误读。手写解析器不引入 protobuf 运行时依赖，
 * 结构固定且完全由 JVM 单测覆盖。
 */
internal object ShiguangIndexParser {

    /** 当前支持的索引协议版本（与上游 proto/build_data.py 的 PROTOCOL_VERSION 对齐） */
    const val SUPPORTED_PROTOCOL_VERSION = 2

    fun parse(bytes: ByteArray): ShiguangIndex? {
        if (bytes.isEmpty()) return null
        val reader = ProtoReader(bytes)
        var protocolVersion = 0
        var versionId = ""
        val schools = mutableListOf<ShiguangSchool>()
        while (reader.hasMore()) {
            val (field, wireType) = reader.readTag() ?: return null
            when (field) {
                FIELD_PROTOCOL_VERSION -> {
                    if (wireType != WIRE_VARINT) return null
                    val value = reader.readVarint() ?: return null
                    if (value <= 0 || value > Int.MAX_VALUE) return null
                    protocolVersion = value.toInt()
                }
                FIELD_VERSION_ID -> {
                    if (wireType != WIRE_LENGTH_DELIMITED) return null
                    versionId = (reader.readLengthDelimited() ?: return null).decodeToString()
                }
                FIELD_SCHOOLS -> {
                    if (wireType != WIRE_LENGTH_DELIMITED) return null
                    schools += parseSchool(reader.readLengthDelimited() ?: return null) ?: return null
                }
                else -> if (!reader.skip(wireType)) return null
            }
        }
        // 没有 protocol_version 的载荷视为坏数据：不能假设旧字段布局仍然成立
        if (protocolVersion <= 0) return null
        return ShiguangIndex(
            protocolVersion = protocolVersion,
            versionId = versionId,
            schools = schools,
        )
    }

    private fun parseSchool(bytes: ByteArray): ShiguangSchool? {
        val reader = ProtoReader(bytes)
        var id = ""
        var name = ""
        var initial = ""
        var resourceFolder = ""
        val adapters = mutableListOf<ShiguangAdapterDecl>()
        while (reader.hasMore()) {
            val (field, wireType) = reader.readTag() ?: return null
            when (field) {
                FIELD_SCHOOL_ID -> id = reader.readString(wireType) ?: return null
                FIELD_SCHOOL_NAME -> name = reader.readString(wireType) ?: return null
                FIELD_SCHOOL_INITIAL -> initial = reader.readString(wireType) ?: return null
                FIELD_SCHOOL_RESOURCE_FOLDER ->
                    resourceFolder = reader.readString(wireType) ?: return null
                FIELD_SCHOOL_ADAPTERS -> {
                    if (wireType != WIRE_LENGTH_DELIMITED) return null
                    adapters += parseAdapter(reader.readLengthDelimited() ?: return null) ?: return null
                }
                else -> if (!reader.skip(wireType)) return null
            }
        }
        return ShiguangSchool(
            id = id,
            name = name,
            initial = initial,
            resourceFolder = resourceFolder,
            adapters = adapters,
        )
    }

    private fun parseAdapter(bytes: ByteArray): ShiguangAdapterDecl? {
        val reader = ProtoReader(bytes)
        var adapterId = ""
        var adapterName = ""
        var category = ShiguangAdapterCategory.UNKNOWN
        var assetPath = ""
        var importUrl: String? = null
        var description = ""
        var maintainer = ""
        while (reader.hasMore()) {
            val (field, wireType) = reader.readTag() ?: return null
            when (field) {
                FIELD_ADAPTER_ID -> adapterId = reader.readString(wireType) ?: return null
                FIELD_ADAPTER_NAME -> adapterName = reader.readString(wireType) ?: return null
                FIELD_ADAPTER_CATEGORY -> {
                    if (wireType != WIRE_VARINT) return null
                    val value = reader.readVarint() ?: return null
                    category = ShiguangAdapterCategory.fromWire(value.toInt())
                }
                FIELD_ADAPTER_ASSET_PATH -> assetPath = reader.readString(wireType) ?: return null
                FIELD_ADAPTER_IMPORT_URL -> importUrl = reader.readString(wireType) ?: return null
                FIELD_ADAPTER_DESCRIPTION ->
                    description = reader.readString(wireType) ?: return null
                FIELD_ADAPTER_MAINTAINER ->
                    maintainer = reader.readString(wireType) ?: return null
                else -> if (!reader.skip(wireType)) return null
            }
        }
        return ShiguangAdapterDecl(
            adapterId = adapterId,
            adapterName = adapterName,
            category = category,
            assetPath = assetPath,
            importUrl = importUrl,
            description = description,
            maintainer = maintainer,
        )
    }

    /** proto3 wire type：0 = varint，1 = 64-bit，2 = length-delimited，5 = 32-bit */
    private const val WIRE_VARINT = 0
    private const val WIRE_64_BIT = 1
    private const val WIRE_LENGTH_DELIMITED = 2
    private const val WIRE_32_BIT = 5

    private const val FIELD_PROTOCOL_VERSION = 1
    private const val FIELD_VERSION_ID = 2
    private const val FIELD_SCHOOLS = 3

    private const val FIELD_SCHOOL_ID = 1
    private const val FIELD_SCHOOL_NAME = 2
    private const val FIELD_SCHOOL_INITIAL = 3
    private const val FIELD_SCHOOL_RESOURCE_FOLDER = 4
    private const val FIELD_SCHOOL_ADAPTERS = 5

    private const val FIELD_ADAPTER_ID = 1
    private const val FIELD_ADAPTER_NAME = 2
    private const val FIELD_ADAPTER_CATEGORY = 3
    private const val FIELD_ADAPTER_ASSET_PATH = 4
    private const val FIELD_ADAPTER_IMPORT_URL = 5
    private const val FIELD_ADAPTER_DESCRIPTION = 6
    private const val FIELD_ADAPTER_MAINTAINER = 7
}

/** 索引顶层：`protocol_version` 与 `version_id` 由调用方校验/展示，schools 保持上游顺序 */
internal data class ShiguangIndex(
    val protocolVersion: Int,
    val versionId: String,
    val schools: List<ShiguangSchool>,
)

internal data class ShiguangSchool(
    val id: String,
    val name: String,
    val initial: String,
    val resourceFolder: String,
    val adapters: List<ShiguangAdapterDecl>,
)

internal data class ShiguangAdapterDecl(
    val adapterId: String,
    val adapterName: String,
    val category: ShiguangAdapterCategory,
    val assetPath: String,
    val importUrl: String?,
    val description: String,
    val maintainer: String,
)

internal enum class ShiguangAdapterCategory(val wireValue: Int) {
    UNKNOWN(0),
    GENERAL_TOOL(1),
    BACHELOR_AND_ASSOCIATE(2),
    POSTGRADUATE(3),
    ;

    companion object {
        fun fromWire(value: Int): ShiguangAdapterCategory =
            entries.firstOrNull { it.wireValue == value } ?: UNKNOWN
    }
}

/**
 * protobuf wire 读取器：只做「tag + varint/length-delimited + 跳过未知字段」。
 * 任何越界/截断/非法 wire type 都返回 null，由调用方按坏索引处理。
 */
private class ProtoReader(private val bytes: ByteArray) {

    private var position = 0

    fun hasMore(): Boolean = position < bytes.size

    fun readTag(): Pair<Int, Int>? {
        val tag = readVarint() ?: return null
        if (tag <= 0) return null
        val field = (tag ushr 3).toInt()
        val wireType = (tag and 0x7L).toInt()
        if (field <= 0) return null
        return field to wireType
    }

    fun readVarint(): Long? {
        var result = 0L
        var shift = 0
        while (shift < 64) {
            if (position >= bytes.size) return null
            val byte = bytes[position++].toInt() and 0xFF
            result = result or ((byte.toLong() and 0x7F) shl shift)
            if (byte and 0x80 == 0) return result
            shift += 7
        }
        return null
    }

    fun readLengthDelimited(): ByteArray? {
        val length = readVarint() ?: return null
        if (length < 0 || length > Int.MAX_VALUE) return null
        val size = length.toInt()
        if (size < 0 || size > bytes.size - position) return null
        val value = bytes.copyOfRange(position, position + size)
        position += size
        return value
    }

    fun readString(wireType: Int): String? {
        if (wireType != WIRE_LENGTH_DELIMITED) return null
        return readLengthDelimited()?.decodeToString()
    }

    fun skip(wireType: Int): Boolean = when (wireType) {
        WIRE_VARINT -> readVarint() != null
        WIRE_64_BIT -> advance(8)
        WIRE_LENGTH_DELIMITED -> readLengthDelimited() != null
        WIRE_32_BIT -> advance(4)
        else -> false
    }

    private fun advance(count: Int): Boolean {
        if (count < 0 || position + count > bytes.size) return false
        position += count
        return true
    }

    private companion object {
        const val WIRE_VARINT = 0
        const val WIRE_64_BIT = 1
        const val WIRE_LENGTH_DELIMITED = 2
        const val WIRE_32_BIT = 5
    }
}
