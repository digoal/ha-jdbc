/*
 * HA-JDBC: High-Availability JDBC
 * Copyright (c) 2004-2008 Paul Ferraro
 * 
 * This library is free software; you can redistribute it and/or modify it 
 * under the terms of the GNU Lesser General Public License as published by the 
 * Free Software Foundation; either version 2.1 of the License, or (at your 
 * option) any later version.
 * 
 * This library is distributed in the hope that it will be useful, but WITHOUT
 * ANY WARRANTY; without even the implied warranty of MERCHANTABILITY or 
 * FITNESS FOR A PARTICULAR PURPOSE. See the GNU Lesser General Public License 
 * for more details.
 * 
 * You should have received a copy of the GNU Lesser General Public License
 * along with this library; if not, write to the Free Software Foundation, 
 * Inc., 59 Temple Place, Suite 330, Boston, MA 02111-1307 USA
 * 
 * Contact: ferraro@users.sourceforge.net
 */
package net.sf.hajdbc.sql.xa;

import java.sql.Connection;
import java.sql.SQLException;

import javax.sql.XAConnection;
import javax.sql.XADataSource;

import net.sf.hajdbc.management.Managed;
import net.sf.hajdbc.sql.CommonDataSourceDatabase;

/**
 * A database described by an {@link XADataSource}.
 * @author Paul Ferraro
 */
@Managed(description = "Database accessed via a server-side XADataSource")
public class XADataSourceDatabase extends CommonDataSourceDatabase<XADataSource>
{
	private boolean force2PC = false;
	
	/**
	 * @see net.sf.hajdbc.Database#connect(java.lang.Object)
	 */
	@Override
	public Connection connect(XADataSource dataSource) throws SQLException
	{
		XAConnection connection = this.requiresAuthentication() ? dataSource.getXAConnection(this.getUser(), this.getPassword()) : dataSource.getXAConnection();
		
		return connection.getConnection();
	}
	
	/**
	 * {@inheritDoc}
	 * @see net.sf.hajdbc.sql.CommonDataSourceDatabase#getTargetClass()
	 */
	@Override
	protected Class<XADataSource> getTargetClass()
	{
		return XADataSource.class;
	}

	public boolean isForce2PC()
	{
		return this.force2PC;
	}
	
	public void setForce2PC(boolean force)
	{
		this.force2PC = force;
	}
}