package com.fangyi.classpp.data

/**
 * 标签数据层全部结构化错误。message 供日志/断言，未来 UI 按子类模式匹配出文案。
 * 标签规则很少（名字 trim 非空、库内不重名），校验在 TagRepository 写入前内联完成，
 * 不像 Todo 那样单设 Validator。
 */
sealed class TagError(val message: String) {

    /** trim 后为空的名字 */
    data object BlankName : TagError("tag name must not be blank")

    /** 新增标签撞上既有名字（名字是唯一键，Todo.tags 存的即名字） */
    data class DuplicateName(val name: String) : TagError("tag already exists: $name")

    data class NotFound(val name: String) : TagError("tag not found: $name")

    data class PersistFailed(val detail: String) : TagError("persist failed: $detail")
}

/** 标签变更操作结果：错误必须被调用方处理 */
sealed interface TagOpResult {
    data object Ok : TagOpResult
    data class Err(val error: TagError) : TagOpResult
}
