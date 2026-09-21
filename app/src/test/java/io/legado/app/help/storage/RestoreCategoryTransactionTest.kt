package io.legado.app.help.storage

import android.app.Application
import androidx.room.Room
import androidx.room.withTransaction
import io.legado.app.data.AppDatabase
import io.legado.app.data.entities.Book
import io.legado.app.data.entities.BookGroup
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

/**
 * 覆盖 Restore 按类别划分的 Room 事务边界。
 *
 * Restore 会在类别边界捕获异常，因此失败类别必须回滚自身写入，后续类别仍应可执行。
 */
@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [35])
class RestoreCategoryTransactionTest {

    private lateinit var database: AppDatabase

    @Before
    fun setUp() {
        database = Room.inMemoryDatabaseBuilder(
            RuntimeEnvironment.getApplication(),
            AppDatabase::class.java,
        ).allowMainThreadQueries().build()
    }

    @After
    fun tearDown() {
        database.close()
    }

    @Test
    fun `failed category rolls back all writes in that category`() = runBlocking {
        database.bookDao.insert(Book(bookUrl = "book-1", name = "Before", author = "Author"))
        database.openHelper.writableDatabase.execSQL(
            """
            CREATE TRIGGER fail_restore_book_update
            BEFORE UPDATE ON books
            BEGIN
                SELECT RAISE(ABORT, 'forced restore failure');
            END
            """.trimIndent()
        )

        val result = runCatching {
            database.withTransaction {
                database.bookGroupDao.insert(BookGroup(groupId = 1L, groupName = "Transient"))
                database.bookDao.update(
                    Book(bookUrl = "book-1", name = "After", author = "Author")
                )
            }
        }

        assertTrue(result.isFailure)
        assertTrue(database.bookGroupDao.all.isEmpty())
        assertEquals("Before", database.bookDao.getBook("book-1")?.name)
    }

    @Test
    fun `failed category does not prevent the next category transaction`() = runBlocking {
        database.openHelper.writableDatabase.execSQL(
            """
            CREATE TRIGGER fail_restore_book_insert
            BEFORE INSERT ON books
            BEGIN
                SELECT RAISE(ABORT, 'forced restore failure');
            END
            """.trimIndent()
        )

        val failedCategory = runCatching {
            database.withTransaction {
                database.bookGroupDao.insert(BookGroup(groupId = 1L, groupName = "Rolled back"))
                database.bookDao.insert(Book(bookUrl = "broken", name = "Broken", author = "Author"))
            }
        }
        assertTrue(failedCategory.isFailure)
        assertTrue(database.bookGroupDao.all.isEmpty())

        database.withTransaction {
            database.bookGroupDao.insert(BookGroup(groupId = 2L, groupName = "Next category"))
        }

        assertEquals(listOf("Next category"), database.bookGroupDao.all.map { it.groupName })
    }
}
