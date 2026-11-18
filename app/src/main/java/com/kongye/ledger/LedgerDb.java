package com.kongye.ledger;
import androidx.room.*;
@Database(entities={LedgerDb.Record.class},version=1,exportSchema=false)
public abstract class LedgerDb extends RoomDatabase {
 public abstract Records records();
 @Entity(tableName="records") public static class Record {
  @PrimaryKey @androidx.annotation.NonNull public String id="";
  @androidx.annotation.NonNull public String kind="";
  @androidx.annotation.NonNull public String payload="";
 }
 @Dao public interface Records {
  @Query("SELECT * FROM records WHERE kind=:kind") java.util.List<Record> all(String kind);
  @Query("SELECT * FROM records WHERE id=:id LIMIT 1") Record find(String id);
  @Query("SELECT * FROM records") java.util.List<Record> allRecords();
  @Insert(onConflict=OnConflictStrategy.REPLACE) void put(Record record);
  @Query("DELETE FROM records WHERE id=:id") void delete(String id);
 }
}
