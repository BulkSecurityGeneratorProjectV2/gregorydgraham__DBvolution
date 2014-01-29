/*
 * Copyright 2013 gregory.graham.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package nz.co.gregs.dbvolution;

import nz.co.gregs.dbvolution.transactions.DBTransaction;
import java.io.PrintStream;
import java.sql.*;
import java.util.*;
import javax.sql.DataSource;
import nz.co.gregs.dbvolution.actions.DBActionList;
import nz.co.gregs.dbvolution.databases.DBStatement;
import nz.co.gregs.dbvolution.databases.DBTransactionStatement;
import nz.co.gregs.dbvolution.databases.definitions.DBDefinition;
import nz.co.gregs.dbvolution.exceptions.*;
import nz.co.gregs.dbvolution.internal.properties.DBRowWrapperFactory;
import nz.co.gregs.dbvolution.internal.properties.PropertyWrapper;
import nz.co.gregs.dbvolution.transactions.DBRawSQLTransaction;

/**
 * DBDatabase is the repository of all knowledge about your database
 *
 * <p>
 * All DBvolution projects need a DBDatabase object to provide the database
 * connection, login details, and to generate the correct syntax for the
 * database.
 *
 * <p>
 * It also provides quick methods to get and print database values and perform
 * transactions.
 *
 * <p>
 * There should be a subclass for your database already in
 * {@code nz.co.gregs.dbvolution.databases}
 *
 * <p>
 * Very few programmers will need to construct an actual DBDatabase as the
 * subclasses provide most of the required details for connecting to their
 * databases.
 *
 * @author gregory.graham
 */
public abstract class DBDatabase {

    private String driverName = "";
    private String jdbcURL = "";
    private String username = "";
    private String password = null;
    private DataSource dataSource = null;
    private boolean printSQLBeforeExecuting;
    private boolean isInATransaction = false;
    private boolean isInAReadOnlyTransaction = false;
    private DBTransactionStatement transactionStatement;
    private final DBDefinition definition;
    private String databaseName;
    private boolean batchIfPossible = true;
    final DBRowWrapperFactory wrapperFactory = new DBRowWrapperFactory();

    /**
     * Define a new DBDatabase.
     *
     * <p>
     * Most programmers should not call this constructor directly. Check the
     * subclasses in {@code nz.co.gregs.dbvolution} for your particular
     * database.
     *
     * <p>
     * DBDatabase encapsulates the knowledge of the database, in particular the
     * syntax of the database in the DBDefinition and the connection details
     * from a DataSource.
     *
     * @param definition - the subclass of DBDefinition that provides the syntax
     * for your database.
     * @param ds - a DataSource for the required database.
     */
    public DBDatabase(DBDefinition definition, DataSource ds) {
        this.definition = definition;
        this.dataSource = ds;
    }

    /**
     * Define a new DBDatabase.
     *
     * <p>
     * Most programmers should not call this constructor directly. Check the
     * subclasses in {@code nz.co.gregs.dbvolution} for your particular
     * database.
     *
     * <p>
     * Create a new DBDatabase by providing the connection details
     *
     * @param definition - the subclass of DBDefinition that provides the syntax
     * for your database.
     * @param driverName - The name of the JDBC class that is the Driver for
     * this database.
     * @param jdbcURL - The JDBC URL to connect to the database.
     * @param username - The username to login to the database as.
     * @param password - The users password for the database.
     */
    public DBDatabase(DBDefinition definition, String driverName, String jdbcURL, String username, String password) {
        this.definition = definition;
        this.driverName = driverName;
        this.jdbcURL = jdbcURL;
        this.password = password;
        this.username = username;
    }

    private DBTransactionStatement getDBTransactionStatement() {
        final DBStatement dbStatement = getDBStatement();
        if (dbStatement instanceof DBTransactionStatement) {
            return (DBTransactionStatement) dbStatement;
        } else {
            return new DBTransactionStatement(this, dbStatement);
        }
    }

    /**
     * Retrieve the DBStatement used internally.
     *
     * <p>
     * DBStatement is the internal version of {@code java.sql.Statement}
     *
     * <p>
     * However you will not need a DBStatement to use DBvolution. Your path lies
     * elsewhere.
     *
     * @return the DBStatement to be used: either a new one, or the current
     * transaction statement.
     */
    public synchronized DBStatement getDBStatement() {
        Connection connection;
        DBStatement statement;
        if (isInATransaction) {
            statement = this.transactionStatement;
        } else {
            if (this.dataSource == null) {
                try {
                    // load the driver
                    Class.forName(getDriverName());
                } catch (ClassNotFoundException noDriver) {
                    throw new RuntimeException("No Driver Found: please check the driver name is correct and the appropriate libaries have been supplied: DRIVERNAME=" + getDriverName(), noDriver);
                }
                try {
                    connection = DriverManager.getConnection(getJdbcURL(), getUsername(), getPassword());
                } catch (SQLException noConnection) {
                    throw new RuntimeException("Connection Not Established: please check the database URL, username, and password, and that the appropriate libaries have been supplied: URL=" + getJdbcURL() + " USERNAME=" + getUsername(), noConnection);
                }
            } else {
                try {
                    connection = dataSource.getConnection();
                } catch (SQLException noConnection) {
                    throw new RuntimeException("Connection Not Established using the DataSource: please check the datasource - " + dataSource.toString(), noConnection);
                }
            }
            try {
                statement = new DBStatement(this, connection.createStatement());
            } catch (SQLException noConnection) {
                throw new RuntimeException("Unable to create a Statement: please check the database URL, username, and password, and that the appropriate libaries have been supplied: URL=" + getJdbcURL() + " USERNAME=" + getUsername(), noConnection);
            }
        }
        return statement;
    }

    /**
     *
     * Inserts DBRows and Lists of DBRows into the correct tables automatically
     *
     * @param <T> a list of DBRows or a List of DBRows
     * @param objs
     * @return a DBActionList of all the actions performed
     * @throws SQLException
     */
    //@SafeVarargs
    public final <T> DBActionList insert(T... objs) throws SQLException {
        DBActionList changes = new DBActionList();
        for (T obj : objs) {
            if (obj instanceof List) {
                List<?> list = (List<?>) obj;
                if (list.size() > 0 && list.get(0) instanceof DBRow) {
                    @SuppressWarnings("unchecked")
                    List<DBRow> rowList = (List<DBRow>) list;
                    for (DBRow row : rowList) {
                        changes.addAll(this.getDBTable(row).insert(row));
                    }
                }
            } else if (obj instanceof DBRow) {
                DBRow row = (DBRow) obj;
                changes.addAll(this.getDBTable(row).insert(row));
            }
        }
        return changes;
    }

    /**
     *
     * Deletes DBRows and Lists of DBRows from the correct tables automatically
     *
     * @param <T> a list of DBRows or a List of DBRows
     * @param objs
     * @return a DBActionList of all the actions performed
     * @throws SQLException
     */
    //@SafeVarargs
    public final <T> DBActionList delete(T... objs) throws SQLException {
        DBActionList changes = new DBActionList();
        for (T obj : objs) {
            if (obj instanceof List) {
                List<?> list = (List<?>) obj;
                if (list.size() > 0 && list.get(0) instanceof DBRow) {
                    @SuppressWarnings("unchecked")
                    List<DBRow> rowList = (List<DBRow>) list;
                    for (DBRow row : rowList) {
                        changes.addAll(this.getDBTable(row).delete(row));
                    }
                }
            } else if (obj instanceof DBRow) {
                DBRow row = (DBRow) obj;
                changes.addAll(this.getDBTable(row).delete(row));
            }
        }
        return changes;
    }

    /**
     *
     * Updates DBRows and Lists of DBRows in the correct tables automatically
     *
     * @param <T> a list of DBRows or a List of DBRows
     * @param objs
     * @return a DBActionList of the actions performed on the database
     * @throws SQLException
     */
    //@SafeVarargs
    public final <T> DBActionList update(T... objs) throws SQLException {
        DBActionList actions = new DBActionList();
        for (T obj : objs) {
            if (obj instanceof List) {
                List<?> list = (List<?>) obj;
                if (list.size() > 0 && list.get(0) instanceof DBRow) {
                    @SuppressWarnings("unchecked")
                    List<DBRow> rowList = (List<DBRow>) list;
                    for (DBRow row : rowList) {
                        actions.addAll(this.getDBTable(row).update(row));
                    }
                }
            } else if (obj instanceof DBRow) {
                DBRow row = (DBRow) obj;
                actions.addAll(this.getDBTable(row).update(row));
            }
        }
        return actions;
    }

    /**
     *
     * Automatically selects the correct table based on the example supplied and
     * returns the selected rows as a list
     * 
     * <p>See {@link nz.co.gregs.dbvolution.DBTable#getRowsByExample(nz.co.gregs.dbvolution.DBRow)}
     *
     * @param <R>
     * @param exampleRow
     * @return a list of the selected rows
     * @throws SQLException
     */
    public <R extends DBRow> List<R> get(R exampleRow) throws SQLException {
        DBTable<R> dbTable = getDBTable(exampleRow);
        return dbTable.getRowsByExample(exampleRow).toList();
    }

    /**
     *
     * Automatically selects the correct table based on the example supplied and
     * returns the selected rows as a list
     * 
     * <p>See {@link DBTable#getRowsByExample(nz.co.gregs.dbvolution.DBRow, long)}
     *
     * @param <R>
     * @param expectedNumberOfRows
     * @param exampleRow
     * @return a list of the selected rows
     * @throws SQLException
     * @throws UnexpectedNumberOfRowsException
     */
    public <R extends DBRow> List<R> get(Long expectedNumberOfRows, R exampleRow) throws SQLException, UnexpectedNumberOfRowsException {
        if (expectedNumberOfRows == null) {
            return get(exampleRow);
        } else {
            return getDBTable(exampleRow).getRowsByExample(exampleRow, expectedNumberOfRows.longValue()).toList();
        }
    }

    /**
     *
     * creates a query and fetches the rows automatically, based on the examples given 
     *
     * @param rows
     * @return a list of DBQueryRows relating to the selected rows
     * @throws SQLException
     * @see DBQuery
     * @see DBQuery#getAllRows() 
     */
    public List<DBQueryRow> get(DBRow... rows) throws SQLException {
        DBQuery dbQuery = getDBQuery(rows);
        return dbQuery.getAllRows();
    }

    /**
     *
     * Convenience method to print the rows from get(DBRow...)
     *
     * @param rows
     */
    public void print(List<?> rows) {
        for (Object row : rows) {
            System.out.println(row.toString());
        }
    }

    /**
     *
     * creates a query and fetches the rows automatically, based on the examples given
     *
     * @param expectedNumberOfRows
     * @param rows
     * @return a list of DBQueryRows relating to the selected rows
     * @throws SQLException
     * @throws nz.co.gregs.dbvolution.exceptions.UnexpectedNumberOfRowsException
     * @see DBQuery
     * @see DBQuery#getAllRows(long)  
     */
    public List<DBQueryRow> get(Long expectedNumberOfRows, DBRow... rows) throws SQLException, UnexpectedNumberOfRowsException {
        if (expectedNumberOfRows == null) {
            return get(rows);
        } else {
            return getDBQuery(rows).getAllRows(expectedNumberOfRows);
        }
    }

    /**
     *
     * Convenience method to simplify switching from READONLY to COMMITTED
     * transaction
     *
     * @param <V>
     * @param dbTransaction
     * @param commit
     * @return the object returned by the transaction
     * @throws SQLException
     * @throws Exception
     * @see DBTransaction
     * @see DBDatabase#doTransaction(nz.co.gregs.dbvolution.transactions.DBTransaction) 
     * @see DBDatabase#doReadOnlyTransaction(nz.co.gregs.dbvolution.transactions.DBTransaction) 
     */
    synchronized public <V> V doTransaction(DBTransaction<V> dbTransaction, Boolean commit) throws SQLException, Exception {
        if (commit) {
            return doTransaction(dbTransaction);
        } else {
            return doReadOnlyTransaction(dbTransaction);
        }
    }

    /**
     * Performs the transaction on this database.
     * 
     * <p>If there is an exception of any kind the transaction is rolled back and no changes are made.
     * 
     * <p>Otherwise the transaction is committed and changes are made permanent
     *
     * @param <V>
     * @param dbTransaction
     * @return the object returned by the transaction
     * @throws SQLException
     * @throws Exception     
     * @see DBTransaction
     */
    synchronized public <V> V doTransaction(DBTransaction<V> dbTransaction) throws SQLException, Exception {
        V returnValues = null;
        Connection connection;
        this.transactionStatement = getDBTransactionStatement();
        try {
            this.isInATransaction = true;
            connection = transactionStatement.getConnection();
            connection.setAutoCommit(false);
            try {
                returnValues = dbTransaction.doTransaction(this);
                connection.commit();
                System.err.println("Transaction Successful: Commit Performed");
                connection.setAutoCommit(true);
            } catch (Exception ex) {
                connection.rollback();
                System.err.println("Exception Occurred: ROLLBACK Performed");
                throw ex;
            } finally {
                connection.setAutoCommit(true);
                connection.close();
            }
        } finally {
            this.transactionStatement.transactionFinished();
            this.isInATransaction = false;
            transactionStatement = null;
        }
        return returnValues;
    }

    /**
     * Performs the transaction on this database without making changes.
     * 
     * <p>If there is an exception of any kind the transaction is rolled back and no changes are made.
     * 
     * <p>If no exception occurs, the transaction is still rolled back and no changes are made
     *
     * @param <V>
     * @param dbTransaction
     * @return the object returned by the transaction
     * @throws SQLException
     * @throws Exception
     * @see DBTransaction
     */
    synchronized public <V> V doReadOnlyTransaction(DBTransaction<V> dbTransaction) throws SQLException, Exception {
        Connection connection;
        V returnValues = null;
        boolean wasReadOnly = false;
        boolean wasAutoCommit = true;

        this.transactionStatement = getDBTransactionStatement();
        try {
            this.isInATransaction = true;
            this.isInAReadOnlyTransaction = true;

            connection = transactionStatement.getConnection();
            wasReadOnly = connection.isReadOnly();
            wasAutoCommit = connection.getAutoCommit();

            connection.setAutoCommit(false);
            try {
                returnValues = dbTransaction.doTransaction(this);
                connection.rollback();
                System.err.println("Transaction Successful: ROLLBACK Performed");
            } catch (Exception ex) {
                connection.rollback();
                System.err.println("Exception Occurred: ROLLBACK Performed");
                throw ex;
            } finally {
                connection.setAutoCommit(wasAutoCommit);
                connection.close();
            }
        } finally {
            this.transactionStatement.transactionFinished();
            this.isInAReadOnlyTransaction = false;
            this.isInATransaction = false;
            transactionStatement = null;
        }
        return returnValues;
    }

    /**
     * Convenience method to implement a DBScript on this database
     *
     * equivalent to script.implement(this);
     *
     * @param script
     * @return a DBActionList provided by the script
     * @throws Exception
     */
    public DBActionList implement(DBScript script) throws Exception {
        return script.implement(this);
    }

    /**
     *
     * Convenience method to test a DBScript on this database
     *
     * equivalent to script.test(this);
     *
     * @param script
     * @return a DBActionList provided by the script
     * @throws Exception
     */
    public DBActionList test(DBScript script) throws Exception {
        return script.test(this);
    }

    /**
     * @return the driverName
     */
    public String getDriverName() {
        return driverName;
    }

    /**
     * @return the jdbcURL
     */
    public String getJdbcURL() {
        return jdbcURL;
    }

    /**
     * @return the username
     */
    public String getUsername() {
        return username;
    }

    /**
     * @return the password
     */
    public String getPassword() {
        return password;
    }

    /**
     *
     * @param <R>
     * @param example
     * @return a DBTable instance for the example provided
     */
    public <R extends DBRow> DBTable<R> getDBTable(R example) {
        return DBTable.getInstance(this, example);
    }

    /**
     *
     * @param examples
     * @return a DBQuery with the examples as required tables
     */
    public DBQuery getDBQuery(DBRow... examples) {
        return DBQuery.getInstance(this, examples);
    }

    public void setPrintSQLBeforeExecuting(boolean b) {
        printSQLBeforeExecuting = b;
    }

    /**
     * @return the printSQLBeforeExecuting
     */
    public boolean isPrintSQLBeforeExecuting() {
        return printSQLBeforeExecuting;
    }

    public void printSQLIfRequested(String sqlString) {
        printSQLIfRequested(sqlString, System.out);
    }

    protected void printSQLIfRequested(String sqlString, PrintStream out) {
        if (printSQLBeforeExecuting) {
            out.println(sqlString);
        }
    }

    /**
     *
     * @param newTableRow
     * @throws SQLException
     */
    public void createTable(DBRow newTableRow) throws SQLException, AutoCommitActionDuringReadOnlyTransactionException {
        if (isInAReadOnlyTransaction) {
            throw new AutoCommitActionDuringReadOnlyTransactionException("DBDatabase.dropTable()");
        }
        StringBuilder sqlScript = new StringBuilder();
        List<PropertyWrapper> pkFields = new ArrayList<PropertyWrapper>();
        String lineSeparator = System.getProperty("line.separator");
        // table name

        sqlScript.append(definition.getCreateTableStart()).append(definition.formatTableName(newTableRow)).append(definition.getCreateTableColumnsStart()).append(lineSeparator);

        // columns
        String sep = "";
        String nextSep = definition.getCreateTableColumnsSeparator();
        List<PropertyWrapper> fields = newTableRow.getPropertyWrappers();
        for (PropertyWrapper field : fields) {
            if (field.isColumn()) {
                String colName = field.columnName();
                sqlScript
                        .append(sep)
                        .append(definition.formatColumnName(colName))
                        .append(definition.getCreateTableColumnsNameAndTypeSeparator())
                        .append(definition.getSQLTypeOfDBDatatype(field));
                sep = nextSep + lineSeparator;

                if (field.isPrimaryKey()) {
                    pkFields.add(field);
                }
            }
        }

        // primary keys
        String pkStart = lineSeparator + definition.getCreateTablePrimaryKeyClauseStart();
        String pkMiddle = definition.getCreateTablePrimaryKeyClauseMiddle();
        String pkEnd = definition.getCreateTablePrimaryKeyClauseEnd() + lineSeparator;
        String pkSep = pkStart;
        for (PropertyWrapper field : pkFields) {
            sqlScript.append(pkSep).append(definition.formatColumnName(field.columnName()));
            pkSep = pkMiddle;
        }
        if (!pkSep.equalsIgnoreCase(pkStart)) {
            sqlScript.append(pkEnd);
        }

        //finish
        sqlScript.append(definition.getCreateTableColumnsEnd()).append(lineSeparator).append(definition.endSQLStatement());
        String sqlString = sqlScript.toString();
        printSQLIfRequested(sqlString);
        getDBStatement().execute(sqlString);
    }

    public void dropTable(DBRow tableRow) throws SQLException, AutoCommitActionDuringReadOnlyTransactionException {
        if (isInAReadOnlyTransaction) {
            throw new AutoCommitActionDuringReadOnlyTransactionException("DBDatabase.dropTable()");
        }
        StringBuilder sqlScript = new StringBuilder();

        sqlScript.append(definition.getDropTableStart()).append(definition.formatTableName(tableRow)).append(definition.endSQLStatement());
        String sqlString = sqlScript.toString();
        printSQLIfRequested(sqlString);
        getDBStatement().execute(sqlString);
    }

    /**
     *
     * The easy way to drop a table that might not exist.
     *
     * @param <TR>
     * @param tableRow
     */
    @SuppressWarnings("empty-statement")
    public <TR extends DBRow> void dropTableNoExceptions(TR tableRow) {
        try {
            this.dropTable(tableRow);
        } catch (Exception exp) {
            ;
        }
    }

    public DBDefinition getDefinition() {
        return definition;
    }

    public boolean willCreateBlankQuery(DBRow row) {
        return row.willCreateBlankQuery(this);
    }

    public void dropDatabase() throws Exception, UnsupportedOperationException, AutoCommitActionDuringReadOnlyTransactionException {
        if (isInAReadOnlyTransaction) {
            throw new AutoCommitActionDuringReadOnlyTransactionException("DBDatabase.dropDatabase()");
        }

        String dropStr = getDefinition().getDropDatabase(getDatabaseName());//;
        printSQLIfRequested(dropStr);

        this.doTransaction(new DBRawSQLTransaction(dropStr));
    }

    public String getDatabaseName() {
        return databaseName;
    }

    protected String setDatabaseName(String databaseName) {
        return this.databaseName = databaseName;
    }

    public boolean batchSQLStatementsWhenPossible() {
        return batchIfPossible;
    }

    public void setBatchSQLStatementsWhenPossible(boolean batchSQLStatementsWhenPossible) {
        batchIfPossible = batchSQLStatementsWhenPossible;
    }
}
