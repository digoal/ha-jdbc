package net.sf.ha.jdbc;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.util.Map;

/**
 * Contains a map of <code>Database</code> -&gt; database connection factory (i.e. Driver, DataSource, ConnectionPoolDataSource, XADataSource)
 * @author  Paul Ferraro
 * @version $Revision$
 * @since   1.0
 */
public class DatabaseCluster extends SQLProxy
{
	private DatabaseClusterDescriptor descriptor;
	private DatabaseClusterManager manager;
	
	/**
	 * Constructs a new DatabaseCluster.
	 * @param name
	 * @param databaseMap
	 * @param validateSQL
	 */
	protected DatabaseCluster(DatabaseClusterManager manager, DatabaseClusterDescriptor descriptor, Map databaseMap)
	{
		super(databaseMap);
		
		this.descriptor = descriptor;
		this.manager = manager;
	}
	
	/**
	 * @see net.sf.ha.jdbc.SQLProxy#getDatabaseCluster()
	 */
	protected DatabaseCluster getDatabaseCluster()
	{
		return this;
	}
	
	public DatabaseClusterDescriptor getDescriptor()
	{
		return this.descriptor;
	}
	
	/**
	 * @param database
	 * @return
	 */
	public boolean isActive(Database database)
	{
		Connection connection = null;
		PreparedStatement statement = null;
		
		Object object = this.getObject(database);
		
		try
		{
			connection = database.connect(object);
			
			statement = connection.prepareStatement(this.descriptor.getValidateSQL());
			
			statement.executeQuery();
			
			return true;
		}
		catch (SQLException e)
		{
			return false;
		}
		finally
		{
			if (statement != null)
			{
				try
				{
					statement.close();
				}
				catch (SQLException e)
				{
					// Ignore
				}
			}

			if (connection != null)
			{
				try
				{
					connection.close();
				}
				catch (SQLException e)
				{
					// Ignore
				}
			}
		}
	}
	
	public void deactivate(Database database)
	{
		this.manager.deactivate(this.descriptor.getName(), database);
	}
}
