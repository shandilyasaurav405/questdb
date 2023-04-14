/*******************************************************************************
 *     ___                  _   ____  ____
 *    / _ \ _   _  ___  ___| |_|  _ \| __ )
 *   | | | | | | |/ _ \/ __| __| | | |  _ \
 *   | |_| | |_| |  __/\__ \ |_| |_| | |_) |
 *    \__\_\\__,_|\___||___/\__|____/|____/
 *
 *  Copyright (c) 2014-2019 Appsicle
 *  Copyright (c) 2019-2023 QuestDB
 *
 *  Licensed under the Apache License, Version 2.0 (the "License");
 *  you may not use this file except in compliance with the License.
 *  You may obtain a copy of the License at
 *
 *  http://www.apache.org/licenses/LICENSE-2.0
 *
 *  Unless required by applicable law or agreed to in writing, software
 *  distributed under the License is distributed on an "AS IS" BASIS,
 *  WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 *  See the License for the specific language governing permissions and
 *  limitations under the License.
 *
 ******************************************************************************/

package io.questdb.test.griffin;

import io.questdb.PropServerConfiguration;
import io.questdb.cairo.*;
import io.questdb.cairo.sql.OperationFuture;
import io.questdb.cairo.wal.ApplyWal2TableJob;
import io.questdb.cairo.wal.CheckWalTransactionsJob;
import io.questdb.cairo.wal.WalUtils;
import io.questdb.griffin.SqlCompiler;
import io.questdb.griffin.SqlException;
import io.questdb.griffin.SqlExecutionContext;
import io.questdb.std.Files;
import io.questdb.std.FilesFacade;
import io.questdb.std.Misc;
import io.questdb.std.datetime.DateFormat;
import io.questdb.std.datetime.microtime.TimestampFormatCompiler;
import io.questdb.std.str.LPSZ;
import io.questdb.std.str.MutableCharSink;
import io.questdb.std.str.Path;
import io.questdb.std.str.StringSink;
import io.questdb.test.AbstractCairoTest;
import io.questdb.test.cairo.DefaultTestCairoConfiguration;
import io.questdb.test.std.TestFilesFacadeImpl;
import io.questdb.test.tools.TestUtils;
import org.junit.*;
import org.junit.rules.TemporaryFolder;
import org.junit.rules.TestName;
import org.junit.runner.RunWith;
import org.junit.runners.Parameterized;

import java.io.File;
import java.io.IOException;
import java.util.Arrays;
import java.util.Collection;

@RunWith(Parameterized.class)
public class TableBackupTest {

    private static final int ERRNO_EIO = 5;
    private static final String TABLE_NAME_SUFFIX = "すばらしい";
    private static final StringSink sink1 = new StringSink();
    private static final StringSink sink2 = new StringSink();
    private final boolean isWal;
    private final String partitionBy;
    @Rule
    public TemporaryFolder temp = new TemporaryFolder();
    @Rule
    public TestName testName = new TestName();
    private CharSequence backupRoot;
    private Path finalBackupPath;
    private int finalBackupPathLen;
    private SqlCompiler mainCompiler;
    private CairoConfiguration mainConfiguration;
    private CairoEngine mainEngine;
    private SqlExecutionContext mainSqlExecutionContext;
    private int mkdirsErrno;
    private int mkdirsErrnoCountDown = 0;
    private Path path;
    private int renameErrno;
    private FilesFacade testFf;

    public TableBackupTest(AbstractCairoTest.WalMode walMode, int partitionBy) {
        isWal = walMode == AbstractCairoTest.WalMode.WITH_WAL;
        this.partitionBy = PartitionBy.toString(partitionBy);
    }

    @Parameterized.Parameters(name = "{0}-{1}")
    public static Collection<Object[]> data() {
        return Arrays.asList(new Object[][]{
                {AbstractCairoTest.WalMode.WITH_WAL, PartitionBy.HOUR},
                {AbstractCairoTest.WalMode.WITH_WAL, PartitionBy.DAY},
//                {AbstractCairoTest.WalMode.WITH_WAL, PartitionBy.WEEK},
                {AbstractCairoTest.WalMode.WITH_WAL, PartitionBy.MONTH},
                {AbstractCairoTest.WalMode.WITH_WAL, PartitionBy.YEAR},

                {AbstractCairoTest.WalMode.NO_WAL, PartitionBy.NONE},
                {AbstractCairoTest.WalMode.NO_WAL, PartitionBy.HOUR},
                {AbstractCairoTest.WalMode.NO_WAL, PartitionBy.DAY},
//                {AbstractCairoTest.WalMode.NO_WAL, PartitionBy.WEEK},
                {AbstractCairoTest.WalMode.NO_WAL, PartitionBy.MONTH},
                {AbstractCairoTest.WalMode.NO_WAL, PartitionBy.YEAR}
        });
    }

    @Before
    public void setup() throws IOException {
        path = new Path();
        finalBackupPath = new Path();
        mkdirsErrno = -1;
        renameErrno = -1;
        CharSequence root = temp.newFolder(String.format("dbRoot%c%s", Files.SEPARATOR, PropServerConfiguration.DB_DIRECTORY)).getAbsolutePath();
        backupRoot = temp.newFolder("dbBackupRoot").getAbsolutePath();
        testFf = new TestFilesFacadeImpl() {
            private int nextErrno = -1;

            @Override
            public int errno() {
                if (nextErrno != -1) {
                    int errno = nextErrno;
                    nextErrno = -1;
                    return errno;
                }
                return super.errno();
            }

            @Override
            public int mkdirs(Path path, int mode) {
                if (mkdirsErrno != -1 && --mkdirsErrnoCountDown < 1) {
                    nextErrno = mkdirsErrno;
                    mkdirsErrno = -1;
                    mkdirsErrnoCountDown = 0;
                    return -1;
                }
                return super.mkdirs(path, mode);
            }

            @Override
            public int rename(LPSZ from, LPSZ to) {
                if (renameErrno != -1) {
                    nextErrno = renameErrno;
                    renameErrno = -1;
                    return Files.FILES_RENAME_ERR_OTHER;
                }
                return super.rename(from, to);
            }
        };
        mainConfiguration = new DefaultTestCairoConfiguration(root) {
            @Override
            public DateFormat getBackupDirTimestampFormat() {
                return new TimestampFormatCompiler().compile("ddMMMyyyy");
            }

            @Override
            public CharSequence getBackupRoot() {
                return backupRoot;
            }

            @Override
            public FilesFacade getFilesFacade() {
                return testFf;
            }

            @Override
            public int getMetadataPoolCapacity() {
                return 1;
            }
        };
        mainEngine = new CairoEngine(mainConfiguration);
        mainCompiler = new SqlCompiler(mainEngine);
        mainSqlExecutionContext = TestUtils.createSqlExecutionCtx(mainEngine);

        // create dummy mainConfiguration
        File confRoot = new File(PropServerConfiguration.rootSubdir(root, PropServerConfiguration.CONFIG_DIRECTORY));
        Assert.assertTrue(confRoot.mkdirs());
        Assert.assertTrue(new File(confRoot, "server.conf").createNewFile());
        Assert.assertTrue(new File(confRoot, "mime.types").createNewFile());
        Assert.assertTrue(new File(confRoot, "log-file.conf").createNewFile());
        Assert.assertTrue(new File(confRoot, "date.formats").createNewFile());
    }

    @After
    public void tearDown() {
        Misc.free(finalBackupPath);
        Misc.free(path);
        Misc.free(mainSqlExecutionContext);
        Misc.free(mainCompiler);
        Misc.free(mainEngine);
    }

    @Test
    public void testAllTypesPartitionedTable() throws Exception {
        assertMemoryLeak(() -> {
            TableToken tableToken = executeCreateTableStmt(testName.getMethodName());
            backupTable(tableToken);
            setFinalBackupPath();
            assertTables(tableToken);
        });
    }

    @Test
    public void testBackupDatabase() throws Exception {
        assertMemoryLeak(() -> {
            testTableName(testName.getMethodName());
            TableToken table1 = executeCreateTableStmt(testName.getMethodName());
            TableToken table2 = executeCreateTableStmt(table1.getTableName() + "_sugus");
            backupDatabase();
            setFinalBackupPath();
            assertTables(table1);
            assertTables(table2);
            assertDatabase();
        });
    }

    @Test
    public void testBackupDatabaseGeohashColumns() throws Exception {
        assertMemoryLeak(() -> {
            TableToken table1 = executeCreateTableStmt(testName.getMethodName());
            TableToken table2 = executeCreateTableStmt(table1.getTableName() + "_sea");
            backupDatabase();
            setFinalBackupPath();
            assertTables(table1);
            assertTables(table2);
            assertDatabase();
        });
    }

    @Test
    public void testBackupDatabaseGeohashColumnsWithColumnTops() throws Exception {
        assertMemoryLeak(() -> {
            String tableName = testTableName(testName.getMethodName());
            execute(
                    "CREATE TABLE " + tableName + " AS (" +
                            "  SELECT" +
                            "      rnd_geohash(2) g1," +
                            "      rnd_geohash(15) g2," +
                            "      timestamp_sequence(0, 1000000000) ts" +
                            "  FROM long_sequence(2)" +
                            ") TIMESTAMP(ts) PARTITION BY " + partitionBy + (isWal ? " WAL" : " BYPASS WAL")
            );
            execute("alter table " + tableName + " add column g4 geohash(30b)");
            execute("alter table " + tableName + " add column g8 geohash(32b)");
            execute(
                    "insert into " + tableName + " " +
                            " select " +
                            " rnd_geohash(2) g1," +
                            " rnd_geohash(15) g2," +
                            " timestamp_sequence(10000000000, 500000000) ts," +
                            " rnd_geohash(31) g4," +
                            " rnd_geohash(42) g8" +
                            " from long_sequence(3)"
            );
            backupDatabase();
            setFinalBackupPath();
            assertTables(mainEngine.getTableToken(tableName));
            assertDatabase();
        });
    }

    @Test
    public void testCompromisedTableName() throws Exception {
        assertMemoryLeak(() -> {
            try {
                TableToken tableToken = executeCreateTableStmt(testName.getMethodName());
                execute("backup table .." + Files.SEPARATOR + tableToken.getTableName());
                Assert.fail();
            } catch (SqlException ex) {
                TestUtils.assertEquals("'.' is an invalid table name", ex.getFlyweightMessage());
            }
        });
    }

    @Test
    public void testIncorrectConfig() throws Exception {
        backupRoot = null;
        assertMemoryLeak(() -> {
            try {
                TableToken tableToken = executeCreateTableStmt(testName.getMethodName());
                backupTable(tableToken);
                Assert.fail();
            } catch (CairoException ex) {
                TestUtils.assertEquals("backup is disabled, server.conf property 'cairo.sql.backup.root' is not set", ex.getFlyweightMessage());
            }
        });
    }

    @Test
    public void testInvalidSql1() throws Exception {
        assertMemoryLeak(() -> {
            try {
                execute("backup something");
                Assert.fail();
            } catch (SqlException ex) {
                Assert.assertEquals(7, ex.getPosition());
                TestUtils.assertEquals("expected 'table' or 'database'", ex.getFlyweightMessage());
            }
        });
    }

    @Test
    public void testInvalidSql2() throws Exception {
        assertMemoryLeak(() -> {
            try {
                execute("backup table");
                Assert.fail();
            } catch (SqlException e) {
                Assert.assertEquals(12, e.getPosition());
                TestUtils.assertEquals("expected a table name", e.getFlyweightMessage());
            }
        });
    }

    @Test
    public void testInvalidSql3() throws Exception {
        assertMemoryLeak(() -> {
            try {
                TableToken tableToken = executeCreateTableStmt(testName.getMethodName());
                execute("backup table " + tableToken.getTableName() + " tb2");
                Assert.fail();
            } catch (SqlException ex) {
                TestUtils.assertEquals("expected ','", ex.getFlyweightMessage());
            }
        });
    }

    @Test
    public void testMissingTable() throws Exception {
        assertMemoryLeak(() -> {
            try {
                TableToken tableToken = executeCreateTableStmt(testName.getMethodName());
                execute("backup table " + tableToken.getTableName() + ", tb2");
                Assert.fail();
            } catch (SqlException e) {
                TestUtils.assertEquals("table does not exist [table=tb2]", e.getFlyweightMessage());
            }
        });
    }

    @Test
    public void testMultipleTable() throws Exception {
        assertMemoryLeak(() -> {
            TableToken token1 = executeCreateTableStmt(testName.getMethodName());
            TableToken token2 = executeCreateTableStmt(token1.getTableName() + "_yip");
            execute("backup table " + token1.getTableName() + ", " + token2.getTableName());
            setFinalBackupPath();
            assertTables(token1);
            assertTables(token2);
        });
    }

    @Test
    public void testRenameFailure() throws Exception {
        assertMemoryLeak(() -> {
            TableToken tableToken = executeCreateTableStmt(testName.getMethodName());
            renameErrno = ERRNO_EIO;
            try {
                backupTable(tableToken);
                Assert.fail();
            } catch (CairoException ex) {
                Assert.assertTrue(ex.getMessage().startsWith("[5] could not rename "));
            }
            backupTable(tableToken);
            setFinalBackupPath(1);
            assertTables(tableToken);
        });
    }

    @Test
    public void testSimpleTable() throws Exception {
        assertMemoryLeak(() -> {
            TableToken tableToken = executeCreateTableStmt(testName.getMethodName());
            backupTable(tableToken);
            setFinalBackupPath();
            assertTables(tableToken);
        });
    }

    @Test
    public void testSuccessiveBackups() throws Exception {
        assertMemoryLeak(() -> {
            String tableName = testTableName(testName.getMethodName());
            execute(
                    "CREATE TABLE " + tableName + " AS (" +
                            "  SELECT" +
                            "      rnd_symbol(4,4,4,2) sym," +
                            "      rnd_double(2) d," +
                            "      timestamp_sequence(0, 1000000000) ts" +
                            "  FROM long_sequence(10)" +
                            ") TIMESTAMP(ts) PARTITION BY " + partitionBy + (isWal ? " WAL" : " BYPASS WAL"));
            TableToken tableToken = mainEngine.getTableToken(tableName);
            backupTable(tableToken);
            setFinalBackupPath();
            StringSink firstBackup = new StringSink();
            selectAll(tableToken, false, sink1);
            selectAll(tableToken, true, firstBackup);
            Assert.assertEquals(sink1, firstBackup);

            execute(
                    "insert into " + tableName +
                            " select * from (" +
                            " select rnd_symbol(4,4,4,2) sym, rnd_double(2) d, timestamp_sequence(10000000000, 500000000) ts from long_sequence(5)" +
                            ") timestamp(ts)"
            );
            backupTable(tableToken);

            setFinalBackupPath();
            selectAll(tableToken, true, sink2);
            Assert.assertNotEquals(sink1, sink2);

            // Check previous backup is unaffected
            setFinalBackupPath(1);
            assertTables(tableToken);
        });
    }

    @Test
    public void testTableBackupDirExists() throws Exception {
        assertMemoryLeak(() -> {
            TableToken tableToken = executeCreateTableStmt(testName.getMethodName());
            try (Path path = new Path()) {
                path.of(mainConfiguration.getBackupRoot()).concat("tmp").concat(tableToken).slash$();
                Assert.assertEquals(0, TestFilesFacadeImpl.INSTANCE.mkdirs(path, mainConfiguration.getBackupMkDirMode()));
                backupTable(tableToken);
                Assert.fail();
            } catch (CairoException ex) {
                TestUtils.assertContains(ex.getFlyweightMessage(), "backup dir already exists [path=");
                TestUtils.assertContains(ex.getFlyweightMessage(), ", table=" + tableToken.getTableName() + ']');
            }
        });
    }

    @Test
    public void testTableBackupDirNotWritable() throws Exception {
        assertMemoryLeak(() -> {
            TableToken tableToken = executeCreateTableStmt(testName.getMethodName());
            try {
                mkdirsErrno = 13;
                mkdirsErrnoCountDown = 2;
                backupTable(tableToken);
                Assert.fail();
            } catch (CairoException ex) {
                Assert.assertTrue(ex.getMessage().startsWith("[13] could not create backup "));
            }
        });
    }

    private static String testTableName(String tableName) {
        int idx = tableName.indexOf('[');
        return (idx > 0 ? tableName.substring(0, idx) : tableName) + '_' + TABLE_NAME_SUFFIX;
    }

    private void assertDatabase() {
        path.of(mainConfiguration.getRoot()).concat(TableUtils.TAB_INDEX_FILE_NAME).$();
        Assert.assertTrue(Files.exists(path));
        finalBackupPath.trimTo(finalBackupPathLen).concat(mainConfiguration.getDbDirectory()).concat(TableUtils.TAB_INDEX_FILE_NAME).$();
        Assert.assertTrue(Files.exists(finalBackupPath));

        finalBackupPath.trimTo(finalBackupPathLen).concat(PropServerConfiguration.CONFIG_DIRECTORY).slash$();
        final int trimLen = finalBackupPath.length();
        Assert.assertTrue(Files.exists(finalBackupPath.concat("server.conf").$()));
        Assert.assertTrue(Files.exists(finalBackupPath.trimTo(trimLen).concat("mime.types").$()));
        Assert.assertTrue(Files.exists(finalBackupPath.trimTo(trimLen).concat("log-file.conf").$()));
        Assert.assertTrue(Files.exists(finalBackupPath.trimTo(trimLen).concat("date.formats").$()));

        if (isWal) {
            path.parent().concat(WalUtils.TABLE_REGISTRY_NAME_FILE).put(".0").$();
            Assert.assertTrue(Files.exists(path));
            finalBackupPath.trimTo(finalBackupPathLen).concat(mainConfiguration.getDbDirectory()).concat(WalUtils.TABLE_REGISTRY_NAME_FILE).put(".0").$();
            Assert.assertTrue(Files.exists(finalBackupPath));
        }
    }

    private void assertMemoryLeak(TestUtils.LeakProneCode code) throws Exception {
        TestUtils.assertMemoryLeak(() -> {
            try {
                code.run();
                mainEngine.releaseInactive();
                Assert.assertEquals(0, mainEngine.getBusyWriterCount());
                Assert.assertEquals(0, mainEngine.getBusyReaderCount());
            } finally {
                mainEngine.clear();
            }
        });
    }

    private void assertTableTxnSeq() {
        if (isWal) {
            path.of(mainConfiguration.getRoot()).concat(TableUtils.TAB_INDEX_FILE_NAME).$();
            Assert.assertTrue(Files.exists(path));
            finalBackupPath.trimTo(finalBackupPathLen).concat(mainConfiguration.getDbDirectory()).concat(TableUtils.TAB_INDEX_FILE_NAME).$();
            Assert.assertTrue(Files.exists(finalBackupPath));
        }
    }

    private void assertTables(TableToken tableToken) throws Exception {
        selectAll(tableToken, false, sink1);
        selectAll(tableToken, true, sink2);
        TestUtils.assertEquals(sink1, sink2);
    }

    private void backupDatabase() throws SqlException {
        execute("BACKUP DATABASE");
    }

    private void backupTable(TableToken tableToken) throws SqlException {
        execute("BACKUP TABLE '" + tableToken.getTableName() + '\'');
    }

    private void drainWalQueue() {
        if (isWal) {
            try (final ApplyWal2TableJob walApplyJob = new ApplyWal2TableJob(mainEngine, 1, 1, null)) {
                walApplyJob.drain(0);
                new CheckWalTransactionsJob(mainEngine).run(0);
                walApplyJob.drain(0);
            }
        }
    }

    private void execute(CharSequence query) throws SqlException {
        try (OperationFuture future = mainCompiler.compile(query, mainSqlExecutionContext).execute(null)) {
            future.await();
        }
        drainWalQueue();
    }

    private TableToken executeCreateTableStmt(String tableName) throws SqlException {
        String finalTableName = testTableName(tableName);
        String create = "CREATE TABLE " + finalTableName + " AS (" +
                "  SELECT" +
                "      rnd_boolean() bool," +
                "      rnd_char() char," +
                "      rnd_byte(2,50) byte," +
                "      rnd_short() short1," +
                "      rnd_short(10,1024) short2," +
                "      rnd_int() int1," +
                "      rnd_int(0, 30, 2) int2," +
                "      rnd_long() long1," +
                "      rnd_long(100,200,2) long2," +
                "      rnd_float(2) float," +
                "      rnd_double(2) double," +
                "      rnd_date(to_date('2015', 'yyyy'), to_date('2016', 'yyyy'), 2) date," +
                "      rnd_timestamp(" +
                "          to_timestamp('2015', 'yyyy'), " +
                "          to_timestamp('2016', 'yyyy'), " +
                "          2) timestamp1," +
                "      timestamp_sequence(0, 1000000000) timestamp2," +
                "      rnd_symbol(4,4,4,2) symbol," +
                "      rnd_str(3,3,2) string," +
                "      rnd_bin(10, 20, 2) binary," +
                "      rnd_geohash(7) g7," +
                "      rnd_geohash(15) g15," +
                "      rnd_geohash(23) g23," +
                "      rnd_geohash(31) g31," +
                "      rnd_geohash(60) g60," +
                "      rnd_uuid4() uuid," +
                "      rnd_long256() long256" +
                "  FROM long_sequence(10)" +
                ") TIMESTAMP(timestamp2) PARTITION BY " + partitionBy + (isWal ? " WAL" : " BYPASS WAL");

        try (OperationFuture future = mainCompiler.compile(create, mainSqlExecutionContext).execute(null)) {
            future.await();
        }
        drainWalQueue();
        return mainEngine.getTableToken(finalTableName);
    }

    private void selectAll(TableToken tableToken, boolean backup, MutableCharSink sink) throws Exception {
        CairoEngine engine = mainEngine;
        SqlCompiler compiler = mainCompiler;
        SqlExecutionContext context = mainSqlExecutionContext;
        try {
            if (backup) {
                engine = new CairoEngine(mainConfiguration);
                compiler = new SqlCompiler(engine);
                context = TestUtils.createSqlExecutionCtx(engine);
            }
            TestUtils.printSql(compiler, context, tableToken.getTableName(), sink);
        } finally {
            if (backup) {
                Misc.free(engine);
                Misc.free(compiler);
                Misc.free(context);
            }
        }
    }

    private void setFinalBackupPath() {
        setFinalBackupPath(0);
    }

    private void setFinalBackupPath(int n) {
        DateFormat timestampFormat = mainConfiguration.getBackupDirTimestampFormat();
        finalBackupPath.of(mainConfiguration.getBackupRoot()).slash();
        timestampFormat.format(mainConfiguration.getMicrosecondClock().getTicks(), mainConfiguration.getDefaultDateLocale(), null, finalBackupPath);
        if (n > 0) {
            finalBackupPath.put('.');
            finalBackupPath.put(n);
        }
        finalBackupPath.slash$();
        finalBackupPathLen = finalBackupPath.length();
        finalBackupPath.trimTo(finalBackupPathLen).concat(PropServerConfiguration.DB_DIRECTORY).slash$();
    }
}
