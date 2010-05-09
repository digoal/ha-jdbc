/*
 * HA-JDBC: High-Availability JDBC
 * Copyright 2004-2009 Paul Ferraro
 * 
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU Lesser General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 * 
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU Lesser General Public License for more details.
 * 
 * You should have received a copy of the GNU Lesser General Public License
 * along with this program.  If not, see <http://www.gnu.org/licenses/>.
 */
package net.sf.hajdbc.sync;

import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.sql.Types;
import java.text.MessageFormat;
import java.util.Collection;
import java.util.HashMap;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Future;

import net.sf.hajdbc.Database;
import net.sf.hajdbc.Dialect;
import net.sf.hajdbc.Messages;
import net.sf.hajdbc.SynchronizationContext;
import net.sf.hajdbc.cache.ForeignKeyConstraint;
import net.sf.hajdbc.cache.SequenceProperties;
import net.sf.hajdbc.cache.TableProperties;
import net.sf.hajdbc.cache.UniqueConstraint;
import net.sf.hajdbc.logging.Level;
import net.sf.hajdbc.logging.Logger;
import net.sf.hajdbc.logging.LoggerFactory;
import net.sf.hajdbc.sql.SQLExceptionFactory;
import net.sf.hajdbc.util.Strings;

/**
 * @author Paul Ferraro
 *
 */
public final class SynchronizationSupport
{
	private static Logger logger = LoggerFactory.getLogger(SynchronizationSupport.class);
	
	private SynchronizationSupport()
	{
		// Hide
	}
	
	/**
	 * Drop all foreign key constraints on the target database
	 * @param <D> 
	 * @param context a synchronization context
	 * @throws SQLException if database error occurs
	 */
	public static <P, D extends Database<P>> void dropForeignKeys(SynchronizationContext<P, D> context) throws SQLException
	{
		Dialect dialect = context.getDialect();
		
		Connection connection = context.getConnection(context.getTargetDatabase());
		
		Statement statement = connection.createStatement();
		
		for (TableProperties table: context.getTargetDatabaseProperties().getTables())
		{
			for (ForeignKeyConstraint constraint: table.getForeignKeyConstraints())
			{
				String sql = dialect.getDropForeignKeyConstraintSQL(constraint);
				
				logger.log(Level.DEBUG, sql);
				
				statement.addBatch(sql);
			}
		}
		
		statement.executeBatch();
		statement.close();
	}
	
	/**
	 * Restores all foreign key constraints on the target database
	 * @param <D> 
	 * @param context a synchronization context
	 * @throws SQLException if database error occurs
	 */
	public static <P, D extends Database<P>> void restoreForeignKeys(SynchronizationContext<P, D> context) throws SQLException
	{
		Dialect dialect = context.getDialect();
		
		Connection connection = context.getConnection(context.getTargetDatabase());
		
		Statement statement = connection.createStatement();
		
		for (TableProperties table: context.getSourceDatabaseProperties().getTables())
		{
			for (ForeignKeyConstraint constraint: table.getForeignKeyConstraints())
			{
				String sql = dialect.getCreateForeignKeyConstraintSQL(constraint);
				
				logger.log(Level.DEBUG, sql);
				
				statement.addBatch(sql);
			}
		}
		
		statement.executeBatch();
		statement.close();
	}
	
	/**
	 * Synchronizes the sequences on the target database with the source database.
	 * @param <D> 
	 * @param context a synchronization context
	 * @throws SQLException if database error occurs
	 */
	public static <P, D extends Database<P>> void synchronizeSequences(final SynchronizationContext<P, D> context) throws SQLException
	{
		Collection<SequenceProperties> sequences = context.getSourceDatabaseProperties().getSequences();

		if (!sequences.isEmpty())
		{
			D sourceDatabase = context.getSourceDatabase();
			
			Set<D> databases = context.getActiveDatabaseSet();

			ExecutorService executor = context.getExecutor();
			
			Dialect dialect = context.getDialect();
			
			Map<SequenceProperties, Long> sequenceMap = new HashMap<SequenceProperties, Long>();
			Map<D, Future<Long>> futureMap = new HashMap<D, Future<Long>>();

			for (SequenceProperties sequence: sequences)
			{
				final String sql = dialect.getNextSequenceValueSQL(sequence);
				
				logger.log(Level.DEBUG, sql);

				for (final D database: databases)
				{
					Callable<Long> task = new Callable<Long>()
					{
						@Override
						public Long call() throws SQLException
						{
							Statement statement = context.getConnection(database).createStatement();
							ResultSet resultSet = statement.executeQuery(sql);
							
							resultSet.next();
							
							long value = resultSet.getLong(1);
							
							statement.close();
							
							return value;
						}
					};
					
					futureMap.put(database, executor.submit(task));				
				}

				try
				{
					Long sourceValue = futureMap.get(sourceDatabase).get();
					
					sequenceMap.put(sequence, sourceValue);
					
					for (D database: databases)
					{
						if (!database.equals(sourceDatabase))
						{
							Long value = futureMap.get(database).get();
							
							if (!value.equals(sourceValue))
							{
								throw new SQLException(Messages.SEQUENCE_OUT_OF_SYNC.getMessage(sequence, database, value, sourceDatabase, sourceValue));
							}
						}
					}
				}
				catch (InterruptedException e)
				{
					throw SQLExceptionFactory.getInstance().createException(e);
				}
				catch (ExecutionException e)
				{
					throw SQLExceptionFactory.getInstance().createException(e.getCause());
				}
			}
			
			Connection targetConnection = context.getConnection(context.getTargetDatabase());
			Statement targetStatement = targetConnection.createStatement();

			for (SequenceProperties sequence: sequences)
			{
				String sql = dialect.getAlterSequenceSQL(sequence, sequenceMap.get(sequence) + 1);
				
				logger.log(Level.DEBUG, sql);
				
				targetStatement.addBatch(sql);
			}
			
			targetStatement.executeBatch();		
			targetStatement.close();
		}
	}
	
	/**
	 * @param <D>
	 * @param context
	 * @throws SQLException
	 */
	public static <P, D extends Database<P>> void synchronizeIdentityColumns(SynchronizationContext<P, D> context) throws SQLException
	{
		Statement sourceStatement = context.getConnection(context.getSourceDatabase()).createStatement();
		Statement targetStatement = context.getConnection(context.getTargetDatabase()).createStatement();
		
		Dialect dialect = context.getDialect();
		
		for (TableProperties table: context.getSourceDatabaseProperties().getTables())
		{
			Collection<String> columns = table.getIdentityColumns();
			
			if (!columns.isEmpty())
			{
				String selectSQL = MessageFormat.format("SELECT max({0}) FROM {1}", Strings.join(columns, "), max("), table.getName()); //$NON-NLS-1$ //$NON-NLS-2$
				
				logger.log(Level.DEBUG, selectSQL);
				
				Map<String, Long> map = new HashMap<String, Long>();
				
				ResultSet resultSet = sourceStatement.executeQuery(selectSQL);
				
				if (resultSet.next())
				{
					int i = 0;
					
					for (String column: columns)
					{
						map.put(column, resultSet.getLong(++i));
					}
				}
				
				resultSet.close();
				
				if (!map.isEmpty())
				{
					for (Map.Entry<String, Long> mapEntry: map.entrySet())
					{
						String alterSQL = dialect.getAlterIdentityColumnSQL(table, table.getColumnProperties(mapEntry.getKey()), mapEntry.getValue() + 1);
						
						if (alterSQL != null)
						{
							logger.log(Level.DEBUG, alterSQL);
							
							targetStatement.addBatch(alterSQL);
						}
					}
					
					targetStatement.executeBatch();
				}
			}
		}
		
		sourceStatement.close();
		targetStatement.close();
	}

	/**
	 * @param <D>
	 * @param context
	 * @throws SQLException
	 */
	public static <P, D extends Database<P>> void dropUniqueConstraints(SynchronizationContext<P, D> context) throws SQLException
	{
		Dialect dialect = context.getDialect();

		Connection connection = context.getConnection(context.getTargetDatabase());
		
		Statement statement = connection.createStatement();
		
		for (TableProperties table: context.getTargetDatabaseProperties().getTables())
		{
			for (UniqueConstraint constraint: table.getUniqueConstraints())
			{
				String sql = dialect.getDropUniqueConstraintSQL(constraint);
				
				logger.log(Level.DEBUG, sql);
				
				statement.addBatch(sql);
			}
		}
		
		statement.executeBatch();
		statement.close();
	}
	
	/**
	 * @param <D>
	 * @param context
	 * @throws SQLException
	 */
	public static <P, D extends Database<P>> void restoreUniqueConstraints(SynchronizationContext<P, D> context) throws SQLException
	{
		Dialect dialect = context.getDialect();

		Connection connection = context.getConnection(context.getTargetDatabase());
		
		Statement statement = connection.createStatement();
		
		for (TableProperties table: context.getSourceDatabaseProperties().getTables())
		{
			// Drop unique constraints on the current table
			for (UniqueConstraint constraint: table.getUniqueConstraints())
			{
				String sql = dialect.getCreateUniqueConstraintSQL(constraint);
				
				logger.log(Level.DEBUG, sql);
				
				statement.addBatch(sql);
			}
		}
		
		statement.executeBatch();
		statement.close();
	}
	
	/**
	 * @param connection
	 */
	public static void rollback(Connection connection)
	{
		try
		{
			connection.rollback();
			connection.setAutoCommit(true);
		}
		catch (SQLException e)
		{
			logger.log(Level.WARN, e, e.toString());
		}
	}
	
	/**
	 * Helper method for {@link java.sql.ResultSet#getObject(int)} with special handling for large objects.
	 * @param resultSet
	 * @param index
	 * @param type
	 * @return the object of the specified type at the specified index from the specified result set
	 * @throws SQLException
	 */
	public static Object getObject(ResultSet resultSet, int index, int type) throws SQLException
	{
		switch (type)
		{
			case Types.BLOB:
			{
				return resultSet.getBlob(index);
			}
			case Types.CLOB:
			{
				return resultSet.getClob(index);
			}
			default:
			{
				return resultSet.getObject(index);
			}
		}
	}
}