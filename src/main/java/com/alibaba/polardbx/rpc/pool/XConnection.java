/*
 * Copyright [2013-2021], Alibaba Group Holding Limited
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *    http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.alibaba.polardbx.rpc.pool;

import com.alibaba.polardbx.common.exception.NotSupportException;
import com.alibaba.polardbx.common.jdbc.BytesSql;
import com.alibaba.polardbx.rpc.GalaxyPrepare.GPTable;
import com.alibaba.polardbx.rpc.XConfig;
import com.alibaba.polardbx.rpc.XLog;
import com.alibaba.polardbx.rpc.client.XClient;
import com.alibaba.polardbx.rpc.client.XSession;
import com.alibaba.polardbx.rpc.compatible.XDataSource;
import com.alibaba.polardbx.rpc.compatible.XPreparedStatement;
import com.alibaba.polardbx.rpc.compatible.XStatement;
import com.alibaba.polardbx.rpc.result.XResult;
import com.google.protobuf.ByteString;
import com.mysql.cj.polarx.protobuf.PolarxNotice;
import com.mysql.cj.x.protobuf.PolarxDatatypes;
import com.mysql.cj.x.protobuf.PolarxExecPlan;

import java.sql.Array;
import java.sql.Blob;
import java.sql.CallableStatement;
import java.sql.Clob;
import java.sql.Connection;
import java.sql.DatabaseMetaData;
import java.sql.NClob;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLClientInfoException;
import java.sql.SQLException;
import java.sql.SQLWarning;
import java.sql.SQLXML;
import java.sql.Savepoint;
import java.sql.Statement;
import java.sql.Struct;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.concurrent.Executor;
import java.util.concurrent.locks.ReentrantReadWriteLock;

/**
 * @version 1.0
 * thread-safe
 */
public class XConnection implements AutoCloseable, Connection {

    private ReentrantReadWriteLock sessionLock = new ReentrantReadWriteLock();
    private XSession session; // Null if closed.
    private boolean initialized = false;

    // Connection & request mode.
    private boolean streamMode = false;
    private boolean compactMetadata = false;
    private boolean withFeedback = false;

    // For stream control.
    private int defaultTokenCount = XConnectionManager.getInstance().getDefaultQueryToken();

    private String traceId = null;

    // Creation time info.
    private long connectNano = 0;
    private long waitNano = 0;

    // For belonging.
    private XDataSource dataSource = null;

    public XConnection(XSession session) {
        this.session = session;
        if (XConnectionManager.getInstance().isEnableTrxLeakCheck()) {
            this.session.recordStack();
        }
    }

    public void init(long timeoutNanos) throws SQLException {
        if (!initialized) {
            // Reset to autocommit.
            sessionLock.readLock().lock();
            final long orgTimeout = networkTimeoutNanos;
            try {
                if (timeoutNanos > 0) {
                    // set this to prevent following pre sql timeout too long
                    networkTimeoutNanos = timeoutNanos;
                }
                session.setAutoCommit(this, true);
                session.refereshConnetionId(this); // only query once
            } finally {
                networkTimeoutNanos = orgTimeout;
                sessionLock.readLock().unlock();
            }
            initialized = true;
        }
    }

    // Must hold the RW lock.
    private void check() throws SQLException {
        if (null == session) {
            throw new SQLException(this + " closed.", "Closed.");
        } else if (!initialized) {
            throw new SQLException(this + " not initialized.", "Not initialized.");
        }
    }

    @Override
    public void close() {
        sessionLock.writeLock().lock();
        try {
            if (session != null) {
                try {
                    // Transaction safety insurance.
                    if (!session.isAutoCommit()) {
                        // Rollback in case of any uncommitted trx.
                        session.execUpdate(this, BytesSql.getBytesSql("rollback"), null, null, true, null);
                    }
                    session.flushIgnorable(this);
                    final XResult lastRequest = session.getLastRequest();
                    if (lastRequest != null && !lastRequest.isGoodAndDone()) {
                        XLog.XLogLogger.error(this + " last req unclosed. " + lastRequest.getSql());
                    }
                } catch (Throwable e) {
                    session.setLastException(e);
                    XLog.XLogLogger.error(e);
                } finally {
                    final XClient parent = session.getClient();
                    try {
                        if (!parent.reuseSession(session)) {
                            parent.dropSession(session);
                        }
                    } catch (Throwable e) {
                        XLog.XLogLogger.error(this + " failed to check session.");
                        XLog.XLogLogger.error(e);
                        try {
                            parent.dropSession(session);
                        } catch (Exception e1) {
                            XLog.XLogLogger.error(this + " failed to drop session.");
                            XLog.XLogLogger.error(e);
                        }
                    } finally {
                        session = null;
                    }
                }
            }
        } finally {
            sessionLock.writeLock().unlock();
        }
    }

    public XSession getSessionUncheck() {
        return session;
    }

    public XSession getSession() throws SQLException {
        sessionLock.readLock().lock();
        try {
            check();
            return session;
        } finally {
            sessionLock.readLock().unlock();
        }
    }

    public boolean isStreamMode() {
        return streamMode;
    }

    public void setStreamMode(boolean streamMode) {
        this.streamMode = streamMode;
    }

    public boolean isCompactMetadata() {
        return compactMetadata;
    }

    public void setCompactMetadata(boolean compactMetadata) {
        this.compactMetadata = compactMetadata;
    }

    public boolean isWithFeedback() {
        return withFeedback;
    }

    public void setWithFeedback(boolean withFeedback) {
        this.withFeedback = withFeedback;
    }

    public int getDefaultTokenCount() {
        return defaultTokenCount;
    }

    public void setDefaultTokenCount(int defaultTokenCount) {
        this.defaultTokenCount = defaultTokenCount;
    }

    public String getTraceId() {
        return traceId;
    }

    public void setTraceId(String traceId) {
        this.traceId = traceId;
    }

    public long getConnectNano() {
        return connectNano;
    }

    public void setConnectNano(long connectNano) {
        this.connectNano = connectNano;
    }

    public long getWaitNano() {
        return waitNano;
    }

    public void setWaitNano(long waitNano) {
        this.waitNano = waitNano;
    }

    public XDataSource getDataSource() {
        return dataSource;
    }

    public void setDataSource(XDataSource dataSource) {
        this.dataSource = dataSource;
    }

    public void kill() throws SQLException {
        kill(true, true);
    }

    public void kill(boolean pushKilled, boolean withClose) throws SQLException {
        sessionLock.readLock().lock();
        try {
            check();
            session.kill(pushKilled);
        } finally {
            sessionLock.readLock().unlock();
        }
        if (withClose) {
            close();
        }
    }

    public void setLazyCtsTransaction() throws SQLException {
        check();
        session.setLazyCtsTransaction();
    }

    public void setLazySnapshotSeq(long lazySnapshotSeq) throws SQLException {
        check();
        session.setLazySnapshotSeq(lazySnapshotSeq);
    }

    public void setLazyCommitSeq(long lazyCommitSeq) throws SQLException {
        check();
        session.setLazyCommitSeq(lazyCommitSeq);
    }

    public XResult execQuery(PolarxExecPlan.ExecPlan.Builder execPlan, BytesSql nativeSql)
        throws SQLException {
        sessionLock.readLock().lock();
        try {
            check();
            return session.execQuery(this, execPlan, nativeSql, false);
        } finally {
            sessionLock.readLock().unlock();
        }
    }

    public XResult execQuery(PolarxExecPlan.ExecPlan.Builder execPlan, BytesSql nativeSql,
                             boolean ignoreResult) throws SQLException {
        sessionLock.readLock().lock();
        try {
            check();
            return session.execQuery(this, execPlan, nativeSql, ignoreResult);
        } finally {
            sessionLock.readLock().unlock();
        }
    }

    public XResult execQuery(String sql) throws SQLException {
        sessionLock.readLock().lock();
        try {
            check();
            return session.execQuery(this, BytesSql.getBytesSql(sql), null, null, false, null);
        } finally {
            sessionLock.readLock().unlock();
        }
    }

    public XResult execQuery(String sql, List<PolarxDatatypes.Any> args) throws SQLException {
        sessionLock.readLock().lock();
        try {
            check();
            return session.execQuery(this, BytesSql.getBytesSql(sql), null, args, false, null);
        } finally {
            sessionLock.readLock().unlock();
        }
    }

    public XResult execQuery(BytesSql sql, byte[] hint, List<PolarxDatatypes.Any> args) throws SQLException {
        sessionLock.readLock().lock();
        try {
            check();
            return session.execQuery(this, sql, hint, args, false, null);
        } finally {
            sessionLock.readLock().unlock();
        }
    }

    public XResult execQuery(String sql, List<PolarxDatatypes.Any> args, boolean ignoreResult)
        throws SQLException {
        sessionLock.readLock().lock();
        try {
            check();
            return session.execQuery(this, BytesSql.getBytesSql(sql), null, args, ignoreResult, null);
        } finally {
            sessionLock.readLock().unlock();
        }
    }

    public XResult execQuery(BytesSql sql, byte[] hint, List<PolarxDatatypes.Any> args, boolean ignoreResult,
                             ByteString digest)
        throws SQLException {
        sessionLock.readLock().lock();
        try {
            check();
            return session.execQuery(this, sql, hint, args, ignoreResult, digest);
        } finally {
            sessionLock.readLock().unlock();
        }
    }

    // Go with galaxy prepare.
    public XResult execGalaxyPrepare(BytesSql sql, byte[] hint, ByteString digest, List<GPTable> tables,
                                     ByteString params, int paramNum, boolean ignoreResult, boolean isUpdate)
        throws SQLException {
        sessionLock.readLock().lock();
        try {
            check();
            return session.execGalaxyPrepare(this, sql, hint, digest, tables, params, paramNum, ignoreResult, isUpdate);
        } finally {
            sessionLock.readLock().unlock();
        }
    }

    public long execUpdate(String sql) throws SQLException {
        sessionLock.readLock().lock();
        try {
            check();
            return session.execUpdate(this, BytesSql.getBytesSql(sql), null, null, false, null).getRowsAffected();
        } finally {
            sessionLock.readLock().unlock();
        }
    }

    public long execUpdate(String sql, List<PolarxDatatypes.Any> args) throws SQLException {
        sessionLock.readLock().lock();
        try {
            check();
            return session.execUpdate(this, BytesSql.getBytesSql(sql), null, args, false, null).getRowsAffected();
        } finally {
            sessionLock.readLock().unlock();
        }
    }

    public long execUpdate(BytesSql sql, byte[] hint, List<PolarxDatatypes.Any> args) throws SQLException {
        sessionLock.readLock().lock();
        try {
            check();
            return session.execUpdate(this, sql, hint, args, false, null).getRowsAffected();
        } finally {
            sessionLock.readLock().unlock();
        }
    }

    public XResult execUpdate(String sql, List<PolarxDatatypes.Any> args, boolean ignoreResult)
        throws SQLException {
        sessionLock.readLock().lock();
        try {
            check();
            return session.execUpdate(this, BytesSql.getBytesSql(sql), null, args, ignoreResult, null);
        } finally {
            sessionLock.readLock().unlock();
        }
    }

    public XResult execUpdate(BytesSql sql, byte[] hint, List<PolarxDatatypes.Any> args, boolean ignoreResult)
        throws SQLException {
        sessionLock.readLock().lock();
        try {
            check();
            return session.execUpdate(this, sql, hint, args, ignoreResult, null);
        } finally {
            sessionLock.readLock().unlock();
        }
    }

    public XResult execUpdate(String sql, byte[] hint, List<PolarxDatatypes.Any> args, boolean ignoreResult,
                              ByteString digest)
        throws SQLException {
        sessionLock.readLock().lock();
        try {
            check();
            return session.execUpdate(this, BytesSql.getBytesSql(sql), hint, args, ignoreResult, digest);
        } finally {
            sessionLock.readLock().unlock();
        }
    }

    public XResult execUpdate(BytesSql sql, byte[] hint, List<PolarxDatatypes.Any> args, boolean ignoreResult,
                              ByteString digest)
        throws SQLException {
        sessionLock.readLock().lock();
        try {
            check();
            return session.execUpdate(this, sql, hint, args, ignoreResult, digest);
        } finally {
            sessionLock.readLock().unlock();
        }
    }

    public XResult execUpdateReturning(String sql, byte[] hint, List<PolarxDatatypes.Any> args, String returning)
        throws SQLException {
        sessionLock.readLock().lock();
        try {
            check();
            return session.execQuery(this, BytesSql.getBytesSql(sql), hint, args, false, null, returning);
        } finally {
            sessionLock.readLock().unlock();
        }
    }

    public XResult execUpdateReturning(BytesSql sql, byte[] hint, List<PolarxDatatypes.Any> args, String returning)
        throws SQLException {
        sessionLock.readLock().lock();
        try {
            check();
            return session.execQuery(this, sql, hint, args, false, null, returning);
        } finally {
            sessionLock.readLock().unlock();
        }
    }

    public long getTSO(int count) throws SQLException {
        sessionLock.readLock().lock();
        try {
            check();
            return session.getTSO(this, count);
        } finally {
            sessionLock.readLock().unlock();
        }
    }

    public void flushNetwork() throws SQLException {
        sessionLock.readLock().lock();
        try {
            check();
            session.flushNetwork();
        } finally {
            sessionLock.readLock().unlock();
        }
    }

    public boolean supportMessageTimestamp() throws SQLException {
        sessionLock.readLock().lock();
        try {
            check();
            return session.supportMessageTimestamp();
        } finally {
            sessionLock.readLock().unlock();
        }
    }

    public boolean supportSingleShardOptimization() throws SQLException {
        sessionLock.readLock().lock();
        try {
            check();
            return session.supportSingleShardOptimization();
        } finally {
            sessionLock.readLock().unlock();
        }
    }

    public boolean supportRawString() throws SQLException {
        sessionLock.readLock().lock();
        try {
            check();
            return session.supportRawString();
        } finally {
            sessionLock.readLock().unlock();
        }
    }

    public long getConnectionId() throws SQLException {
        sessionLock.readLock().lock();
        try {
            check();
            return session.getConnectionId();
        } finally {
            sessionLock.readLock().unlock();
        }
    }

    public XResult getLastUserRequest() throws SQLException {
        sessionLock.readLock().lock();
        try {
            check();
            return session.getLastUserRequest();
        } finally {
            sessionLock.readLock().unlock();
        }
    }

    public void cancel() throws SQLException {
        sessionLock.readLock().lock();
        try {
            check();
            session.cancel();
        } finally {
            sessionLock.readLock().unlock();
        }
    }

    public Throwable getLastException() throws SQLException {
        sessionLock.readLock().lock();
        try {
            check();
            return session.getLastException();
        } finally {
            sessionLock.readLock().unlock();
        }
    }

    public Throwable setLastException(Throwable lastException) throws SQLException {
        sessionLock.readLock().lock();
        try {
            check();
            return session.setLastException(lastException);
        } finally {
            sessionLock.readLock().unlock();
        }
    }

    public void tokenOffer() throws SQLException {
        sessionLock.readLock().lock();
        try {
            check();
            session.tokenOffer(defaultTokenCount);
        } finally {
            sessionLock.readLock().unlock();
        }
    }

    public void setDefaultDB(String defaultDB) throws SQLException {
        sessionLock.readLock().lock();
        try {
            check();
            session.setDefaultDB(defaultDB);
        } finally {
            sessionLock.readLock().unlock();
        }
    }

    public void setSessionVariables(Map<String, Object> newServerVariables)
        throws SQLException {
        sessionLock.readLock().lock();
        try {
            check();
            session.setSessionVariables(this, newServerVariables);
        } finally {
            sessionLock.readLock().unlock();
        }
    }

    public void setGlobalVariables(Map<String, Object> newGlobalVariables) throws SQLException {
        sessionLock.readLock().lock();
        try {
            check();
            session.setGlobalVariables(this, newGlobalVariables);
        } finally {
            sessionLock.readLock().unlock();
        }
    }

    /**
     * Compatible for JDBC connection.
     */

    private Executor networkTimeoutExecutor = null;
    private long networkTimeoutNanos = 0;
    private boolean autoCommit = true;

    public long actualTimeoutNanos() {
        long t = networkTimeoutNanos;
        return 0 == t ? XConfig.DEFAULT_TIMEOUT_NANOS : t;
    }

    @Override
    public Statement createStatement() throws SQLException {
        return new XStatement(this);
    }

    @Override
    public PreparedStatement prepareStatement(String sql) throws SQLException {
        return new XPreparedStatement(this, sql);
    }

    public PreparedStatement prepareStatement(BytesSql sql, byte[] hint) throws SQLException {
        return new XPreparedStatement(this, sql, hint);
    }

    @Override
    public CallableStatement prepareCall(String sql) throws SQLException {
        throw new NotSupportException();
    }

    @Override
    public String nativeSQL(String sql) throws SQLException {
        throw new NotSupportException();
    }

    @Override
    public void setAutoCommit(boolean autoCommit) throws SQLException {
        sessionLock.readLock().lock();
        try {
            check();
            session.setAutoCommit(this, autoCommit);
        } finally {
            sessionLock.readLock().unlock();
        }
        this.autoCommit = autoCommit;
    }

    @Override
    public boolean getAutoCommit() throws SQLException {
        return this.autoCommit;
    }

    @Override
    public void commit() throws SQLException {
        execUpdate("commit");
    }

    @Override
    public void rollback() throws SQLException {
        execUpdate("rollback");
    }

    @Override
    public boolean isClosed() throws SQLException {
        sessionLock.readLock().lock();
        try {
            return null == session;
        } finally {
            sessionLock.readLock().unlock();
        }
    }

    @Override
    public DatabaseMetaData getMetaData() throws SQLException {
        throw new NotSupportException();
    }

    @Override
    public void setReadOnly(boolean readOnly) throws SQLException {
        throw new NotSupportException();
    }

    @Override
    public boolean isReadOnly() throws SQLException {
        throw new NotSupportException();
    }

    @Override
    public void setCatalog(String catalog) throws SQLException {
        throw new NotSupportException();
    }

    @Override
    public String getCatalog() throws SQLException {
        throw new NotSupportException();
    }

    @Override
    public void setTransactionIsolation(int level) throws SQLException {
        sessionLock.readLock().lock();
        try {
            check();
            if (level == session.getIsolation()) {
                return;
            }
            final String sql, levelString;
            switch (level) {
            case Connection.TRANSACTION_READ_UNCOMMITTED:
                sql = "set session transaction isolation level read uncommitted";
                levelString = "READ-UNCOMMITTED";
                break;

            case Connection.TRANSACTION_READ_COMMITTED:
                sql = "set session transaction isolation level read committed";
                levelString = "READ-COMMITTED";
                break;

            case Connection.TRANSACTION_REPEATABLE_READ:
                sql = "set session transaction isolation level repeatable read";
                levelString = "REPEATABLE-READ";
                break;

            case Connection.TRANSACTION_SERIALIZABLE:
                sql = "set session transaction isolation level serializable";
                levelString = "SERIALIZABLE";
                break;

            default:
                throw new SQLException("Unknown transaction isolation level: " + level);
            }
            boolean isStashed = session.stashTransactionSequence();
            try {
                // Set ignore is ok, drop connection when fail.
                session.execUpdate(this, BytesSql.getBytesSql(sql), null, null, true, null);
                session.updateIsolation(levelString);
            } finally {
                if (isStashed) {
                    session.stashPopTransactionSequence();
                }
            }
        } finally {
            sessionLock.readLock().unlock();
        }
    }

    @Override
    public int getTransactionIsolation() throws SQLException {
        sessionLock.readLock().lock();
        try {
            check();
            return session.getIsolation();
        } finally {
            sessionLock.readLock().unlock();
        }
    }

    @Override
    public SQLWarning getWarnings() throws SQLException {
        final XResult last;
        sessionLock.readLock().lock();
        try {
            check();
            last = session.getLastUserRequest();
        } finally {
            sessionLock.readLock().unlock();
        }
        final PolarxNotice.Warning warning = null == last ? null : last.getWarning();
        return null == warning ? null : new SQLWarning(warning.getMsg(), "", warning.getCode());
    }

    @Override
    public void clearWarnings() throws SQLException {
        throw new NotSupportException();
    }

    @Override
    public Statement createStatement(int resultSetType, int resultSetConcurrency) throws SQLException {
        throw new NotSupportException();
    }

    @Override
    public PreparedStatement prepareStatement(String sql, int resultSetType, int resultSetConcurrency)
        throws SQLException {
        if (ResultSet.TYPE_FORWARD_ONLY == resultSetType && ResultSet.CONCUR_READ_ONLY == resultSetConcurrency) {
            return new XPreparedStatement(this, sql);
        }
        throw new NotSupportException();
    }

    @Override
    public CallableStatement prepareCall(String sql, int resultSetType, int resultSetConcurrency) throws SQLException {
        throw new NotSupportException();
    }

    @Override
    public Map<String, Class<?>> getTypeMap() throws SQLException {
        throw new NotSupportException();
    }

    @Override
    public void setTypeMap(Map<String, Class<?>> map) throws SQLException {
        throw new NotSupportException();
    }

    @Override
    public void setHoldability(int holdability) throws SQLException {
        throw new NotSupportException();
    }

    @Override
    public int getHoldability() throws SQLException {
        throw new NotSupportException();
    }

    @Override
    public Savepoint setSavepoint() throws SQLException {
        throw new NotSupportException();
    }

    @Override
    public Savepoint setSavepoint(String name) throws SQLException {
        throw new NotSupportException();
    }

    @Override
    public void rollback(Savepoint savepoint) throws SQLException {
        throw new NotSupportException();
    }

    @Override
    public void releaseSavepoint(Savepoint savepoint) throws SQLException {
        throw new NotSupportException();
    }

    @Override
    public Statement createStatement(int resultSetType, int resultSetConcurrency, int resultSetHoldability)
        throws SQLException {
        return null;
    }

    @Override
    public PreparedStatement prepareStatement(String sql, int resultSetType, int resultSetConcurrency,
                                              int resultSetHoldability) throws SQLException {
        throw new NotSupportException();
    }

    @Override
    public CallableStatement prepareCall(String sql, int resultSetType, int resultSetConcurrency,
                                         int resultSetHoldability) throws SQLException {
        throw new NotSupportException();
    }

    @Override
    public PreparedStatement prepareStatement(String sql, int autoGeneratedKeys) throws SQLException {
        if (Statement.RETURN_GENERATED_KEYS == autoGeneratedKeys) {
            return new XPreparedStatement(this, sql);
        } else {
            throw new NotSupportException();
        }
    }

    @Override
    public PreparedStatement prepareStatement(String sql, int[] columnIndexes) throws SQLException {
        throw new NotSupportException();
    }

    @Override
    public PreparedStatement prepareStatement(String sql, String[] columnNames) throws SQLException {
        throw new NotSupportException();
    }

    @Override
    public Clob createClob() throws SQLException {
        throw new NotSupportException();
    }

    @Override
    public Blob createBlob() throws SQLException {
        throw new NotSupportException();
    }

    @Override
    public NClob createNClob() throws SQLException {
        throw new NotSupportException();
    }

    @Override
    public SQLXML createSQLXML() throws SQLException {
        throw new NotSupportException();
    }

    @Override
    public boolean isValid(int timeout) throws SQLException {
        throw new NotSupportException();
    }

    @Override
    public void setClientInfo(String name, String value) throws SQLClientInfoException {
        throw new NotSupportException();
    }

    @Override
    public void setClientInfo(Properties properties) throws SQLClientInfoException {
        throw new NotSupportException();
    }

    @Override
    public String getClientInfo(String name) throws SQLException {
        throw new NotSupportException();
    }

    @Override
    public Properties getClientInfo() throws SQLException {
        throw new NotSupportException();
    }

    @Override
    public Array createArrayOf(String typeName, Object[] elements) throws SQLException {
        throw new NotSupportException();
    }

    @Override
    public Struct createStruct(String typeName, Object[] attributes) throws SQLException {
        throw new NotSupportException();
    }

    @Override
    public void setSchema(String schema) throws SQLException {
        throw new NotSupportException();
    }

    @Override
    public String getSchema() throws SQLException {
        throw new NotSupportException();
    }

    @Override
    public void abort(Executor executor) throws SQLException {
        throw new NotSupportException();
    }

    @Override
    public void setNetworkTimeout(Executor executor, int milliseconds) throws SQLException {
        networkTimeoutExecutor = executor;
        networkTimeoutNanos = milliseconds * 1000000L;
    }

    @Override
    public int getNetworkTimeout() throws SQLException {
        return (int) (networkTimeoutNanos / 1000000L);
    }

    public void setNetworkTimeoutNanos(long nanos) {
        networkTimeoutNanos = nanos;
    }

    @Override
    public <T> T unwrap(Class<T> iface) throws SQLException {
        if (isWrapperFor(iface)) {
            return (T) this;
        } else {
            throw new SQLException("not a wrapper for " + iface);
        }
    }

    @Override
    public boolean isWrapperFor(Class<?> iface) throws SQLException {
        return XConnection.class.isAssignableFrom(iface);
    }

    @Override
    public String toString() {
        final XSession s = this.session;
        if (null == s) {
            return "XConnection for closed session.";
        }
        return "XConnection for " + s;
    }
}
