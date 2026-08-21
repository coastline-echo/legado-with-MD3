package io.legado.app.data.dao

import androidx.room.*
import io.legado.app.data.entities.Cookie

@Dao
interface CookieDao {

    /** 导入冲突预览使用：只判断 Cookie 是否存在，不读取或改写 Cookie 内容。 */
    @Query("SELECT EXISTS(SELECT 1 FROM cookies WHERE url = :url)")
    fun hasUrl(url: String): Boolean

    @Query("SELECT * FROM cookies Where url = :url")
    fun get(url: String): Cookie?

    @Query("select * from cookies where url like '%|%'")
    fun getOkHttpCookies(): List<Cookie>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    fun insert(vararg cookie: Cookie)

    @Update
    fun update(vararg cookie: Cookie)

    @Query("delete from cookies where url = :url")
    fun delete(url: String)

    @Query("delete from cookies where url like '%|%'")
    fun deleteOkHttp()
}
