package com.fangyi.classpp.data

import com.fangyi.classpp.data.model.TagEntry
import com.fangyi.classpp.data.store.FileTagStore
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/** 标签仓库行为：增删改色的归一化/重名/未找到，以及落盘先行（重启后数据还在） */
class TagRepositoryTest {

    @get:Rule
    val folder = TemporaryFolder()

    private suspend fun open() = TagRepository.create(FileTagStore(folder.root))

    private fun TagOpResult.okOrThrow() {
        assertTrue("expected Ok, got ${(this as? TagOpResult.Err)?.error?.message}", this is TagOpResult.Ok)
    }

    private fun TagOpResult.errOrThrow(): TagError {
        assertTrue("expected Err, got $this", this is TagOpResult.Err)
        return (this as TagOpResult.Err).error
    }

    @Test
    fun `add trims and rejects blank and duplicate`() = runBlocking {
        val repo = open()

        repo.addTag("  紧急  ").okOrThrow()
        assertEquals(listOf(TagEntry("紧急")), repo.tags.value)

        // trim 后为空 → BlankName；重名（trim 后比较）→ DuplicateName
        assertEquals(TagError.BlankName, repo.addTag("   ").errOrThrow())
        assertEquals(TagError.DuplicateName("紧急"), repo.addTag(" 紧急 ").errOrThrow())
        assertEquals(1, repo.tags.value.size)
    }

    @Test
    fun `add keeps creation order and optional color`() = runBlocking {
        val repo = open()

        repo.addTag("紧急").okOrThrow()
        repo.addTag("实验报告", colorArgb = 0xFF22B14C).okOrThrow()

        assertEquals(
            listOf(TagEntry("紧急"), TagEntry("实验报告", colorArgb = 0xFF22B14C)),
            repo.tags.value,
        )
    }

    @Test
    fun `remove unknown name is NotFound`() = runBlocking {
        val repo = open()

        repo.addTag("紧急").okOrThrow()
        assertEquals(TagError.NotFound("不存在"), repo.removeTag("不存在").errOrThrow())

        repo.removeTag("紧急").okOrThrow()
        assertTrue(repo.tags.value.isEmpty())
    }

    @Test
    fun `renameTag updates name in place and persists`() = runBlocking {
        val repo = open()
        repo.addTag("紧急").okOrThrow()
        repo.addTag("实验报告", colorArgb = 0xFF22B14C).okOrThrow()

        // 待办侧引用回写由调用方（TodoRepository.renameTagInTodos）跟进，这里只验标签库
        repo.renameTag("紧急", "  本周重点  ").okOrThrow()
        // 只改名字：颜色与顺序不动
        assertEquals(
            listOf(TagEntry("本周重点"), TagEntry("实验报告", colorArgb = 0xFF22B14C)),
            repo.tags.value,
        )

        val reopened = open()
        assertEquals(
            listOf(TagEntry("本周重点"), TagEntry("实验报告", colorArgb = 0xFF22B14C)),
            reopened.tags.value,
        )
    }

    @Test
    fun `renameTag rejects blank unknown and duplicate, same name is no-op allowed`() = runBlocking {
        val repo = open()
        repo.addTag("紧急").okOrThrow()
        repo.addTag("本周").okOrThrow()

        assertEquals(TagError.BlankName, repo.renameTag("紧急", "   ").errOrThrow())
        assertEquals(TagError.NotFound("不存在"), repo.renameTag("不存在", "新名").errOrThrow())
        assertEquals(TagError.DuplicateName("本周"), repo.renameTag("紧急", " 本周 ").errOrThrow())
        // 与原名同名 = 无操作（允许确认，同分组重命名约定）
        repo.renameTag("紧急", "紧急").okOrThrow()
        assertEquals(listOf(TagEntry("紧急"), TagEntry("本周")), repo.tags.value)
    }

    @Test
    fun `setTags replaces table with normalized order and persists`() = runBlocking {
        val repo = open()
        repo.addTag("紧急").okOrThrow()
        repo.addTag("实验报告", colorArgb = 0xFF22B14C).okOrThrow()
        repo.addTag("本周").okOrThrow()

        // 编辑态确认（拖拽重排）与批量删除共用整表替换：顺序即显示序，归一化同 addTag
        // （trim → 空白丢弃 → 按名去重保留首个，重名条目以首个的颜色为准），颜色随条目保留
        repo.setTags(
            listOf(
                TagEntry(" 本周 "),
                TagEntry("实验报告", colorArgb = 0xFF22B14C),
                TagEntry(""),
                TagEntry("紧急"),
                TagEntry("实验报告"),
            ),
        ).okOrThrow()
        assertEquals(
            listOf(TagEntry("本周"), TagEntry("实验报告", colorArgb = 0xFF22B14C), TagEntry("紧急")),
            repo.tags.value,
        )

        val reopened = open()
        assertEquals(
            listOf(TagEntry("本周"), TagEntry("实验报告", colorArgb = 0xFF22B14C), TagEntry("紧急")),
            reopened.tags.value,
        )
    }

    @Test
    fun `setTagColor updates only the target and null returns to theme default`() = runBlocking {
        val repo = open()

        repo.addTag("紧急").okOrThrow()
        repo.addTag("本周").okOrThrow()

        repo.setTagColor("紧急", 0xFF22B14C).okOrThrow()
        assertEquals(
            listOf(TagEntry("紧急", colorArgb = 0xFF22B14C), TagEntry("本周")),
            repo.tags.value,
        )

        repo.setTagColor("紧急", null).okOrThrow()
        assertEquals(
            listOf(TagEntry("紧急"), TagEntry("本周")),
            repo.tags.value,
        )
        assertEquals(TagError.NotFound("不存在"), repo.setTagColor("不存在", 0xFF22B14C).errOrThrow())
    }

    @Test
    fun `state persists across repositories`() = runBlocking {
        val repo = open()
        repo.addTag("紧急", colorArgb = 0xFF22B14C).okOrThrow()
        repo.addTag("实验报告").okOrThrow()
        repo.removeTag("实验报告").okOrThrow()

        // 同目录新仓库 = 模拟进程重启：磁盘状态原样载入
        val reopened = open()
        assertEquals(LoadState.Ready, reopened.loadState.value)
        assertEquals(listOf(TagEntry("紧急", colorArgb = 0xFF22B14C)), reopened.tags.value)
    }
}
