package io.legado.app.data.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import io.legado.app.data.entities.Cache

@Dao
interface CacheDao {

    @Query("SELECT EXISTS(SELECT 1 FROM caches WHERE `key` = :sourceUrl OR `key` = 'sourceVariable_' || :sourceUrl OR `key` LIKE 'v_' || :sourceUrl || '_%' OR `key` = 'userInfo_' || :sourceUrl OR `key` = 'loginHeader_' || :sourceUrl)")
    fun hasSourceData(sourceUrl: String): Boolean

    @Query("select * from caches where `key` = :key")
    fun get(key: String): Cache?

    @Query("select value from caches where `key` = :key and (deadline = 0 or deadline > :now)")
    fun get(key: String, now: Long): String?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    fun insert(vararg cache: Cache)

    @Query("delete from caches where `key` = :key")
    fun delete(key: String)

    @Query(
        """delete from caches where `key` like 'v_' || :key || '_%'
        or `key` = 'userInfo_' || :key
        or `key` = 'loginHeader_' || :key
        or `key` = 'sourceVariable_' || :key"""
    )
    fun deleteSourceVariables(key: String)

    @Query("delete from caches where deadline > 0 and deadline < :now")
    fun clearDeadline(now: Long)

}
