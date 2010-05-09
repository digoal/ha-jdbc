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
package net.sf.hajdbc.balancer;

import java.util.Collection;
import java.util.Iterator;
import java.util.Set;
import java.util.SortedSet;
import java.util.TreeSet;
import java.util.concurrent.locks.Lock;
import java.util.concurrent.locks.ReentrantLock;

import net.sf.hajdbc.Database;
import net.sf.hajdbc.util.Collections;

/**
 * @author paul
 *
 */
public abstract class AbstractSetBalancer<Z, D extends Database<Z>> extends AbstractBalancer<Z, D>
{
	private final Lock lock = new ReentrantLock();

	private volatile SortedSet<D> databaseSet = Collections.emptySortedSet();

	protected Lock getLock()
	{
		return this.lock;
	}
	
	/**
	 * {@inheritDoc}
	 * @see net.sf.hajdbc.balancer.Balancer#master()
	 */
	@Override
	public D master()
	{
		return this.databaseSet.first();
	}

	/**
	 * {@inheritDoc}
	 * @see net.sf.hajdbc.balancer.Balancer#slaves()
	 */
	@Override
	public Iterable<D> slaves()
	{
		Iterator<D> databases = this.databaseSet.iterator();
		
		if (!databases.hasNext() || ((databases.next() != null) && !databases.hasNext())) return java.util.Collections.emptySet();
		
		D database = databases.next();
		
		if (!databases.hasNext()) return java.util.Collections.singleton(database);
		
		SortedSet<D> slaves = new TreeSet<D>();
		
		slaves.add(database);
		
		do
		{
			slaves.add(databases.next());
		}
		while (databases.hasNext());

		return slaves;
	}

	/**
	 * {@inheritDoc}
	 * @see net.sf.hajdbc.balancer.AbstractBalancer#getDatabaseSet()
	 */
	@Override
	protected Set<D> getDatabaseSet()
	{
		return this.databaseSet;
	}

	/**
	 * @see net.sf.hajdbc.balancer.Balancer#beforeInvocation(net.sf.hajdbc.Database)
	 */
	@Override
	public void beforeInvocation(D database)
	{
		// Do nothing
	}
	
	/**
	 * @see net.sf.hajdbc.balancer.Balancer#afterInvocation(net.sf.hajdbc.Database)
	 */
	@Override
	public void afterInvocation(D database)
	{
		// Do nothing
	}
	
	/**
	 * @see net.sf.hajdbc.balancer.Balancer#remove(net.sf.hajdbc.Database)
	 */
	@SuppressWarnings("unchecked")
	@Override
	public boolean remove(Object database)
	{
		this.lock.lock();
		
		try
		{
			boolean remove = this.databaseSet.contains(database);

			if (remove)
			{
				if (this.databaseSet.size() == 1)
				{
					this.databaseSet = Collections.emptySortedSet();
				}
				else
				{
					SortedSet<D> set = new TreeSet<D>(this.databaseSet);
					
					set.remove(database);
					
					this.databaseSet = set;
				}
				
				this.removed((D) database);
			}
			
			return remove;
		}
		finally
		{
			this.lock.unlock();
		}
	}
	
	/**
	 * Called when a database was removed from the set.
	 * @param database a database descriptor
	 */
	protected abstract void removed(D database);
	
	/**
	 * @see net.sf.hajdbc.balancer.Balancer#add(net.sf.hajdbc.Database)
	 */
	@Override
	public boolean add(D database)
	{
		this.lock.lock();
		
		try
		{
			boolean add = !this.databaseSet.contains(database);
			
			if (add)
			{
				if (this.databaseSet.isEmpty())
				{
					this.databaseSet = Collections.singletonSortedSet(database);
				}
				else
				{
					SortedSet<D> set = new TreeSet<D>(this.databaseSet);
					
					set.add(database);
					
					this.databaseSet = set;
				}
				
				this.added(database);
			}
			
			return add;
		}
		finally
		{
			this.lock.unlock();
		}
	}
	
	/**
	 * Called when a database was added to the set.
	 * @param database a database descriptor
	 */
	protected abstract void added(D database);

	/**
	 * {@inheritDoc}
	 * @see java.util.Set#addAll(java.util.Collection)
	 */
	@Override
	public boolean addAll(Collection<? extends D> databases)
	{
		this.lock.lock();
		
		try
		{
			SortedSet<D> addSet = new TreeSet<D>(this.databaseSet);

			boolean added = addSet.addAll(databases);
			
			if (added)
			{
				Set<D> removeSet = new TreeSet<D>(addSet);
				
				removeSet.removeAll(this.databaseSet);
				
				this.databaseSet = addSet;
				
				for (D database: removeSet)
				{
					this.added(database);
				}
			}
			
			return added;
		}
		finally
		{
			this.lock.unlock();
		}
	}

	/**
	 * {@inheritDoc}
	 * @see java.util.Set#removeAll(java.util.Collection)
	 */
	@Override
	public boolean removeAll(Collection<?> databases)
	{
		this.lock.lock();
		
		try
		{
			SortedSet<D> removeSet = new TreeSet<D>(this.databaseSet);

			boolean removed = removeSet.removeAll(databases);
			
			if (removed)
			{
				Set<D> retainSet = new TreeSet<D>(this.databaseSet);
				
				retainSet.retainAll(databases);
				
				this.databaseSet = removeSet;
				
				for (D database: removeSet)
				{
					this.removed(database);
				}
			}
			
			return removed;
		}
		finally
		{
			this.lock.unlock();
		}
	}

	/**
	 * {@inheritDoc}
	 * @see java.util.Set#retainAll(java.util.Collection)
	 */
	@Override
	public boolean retainAll(Collection<?> databases)
	{
		this.lock.lock();
		
		try
		{
			SortedSet<D> retainSet = new TreeSet<D>(this.databaseSet);

			boolean retained = retainSet.retainAll(databases);
			
			if (retained)
			{
				Set<D> removeSet = new TreeSet<D>(this.databaseSet);
				
				removeSet.removeAll(databases);
				
				this.databaseSet = retainSet;
				
				for (D database: removeSet)
				{
					this.removed(database);
				}
			}
			
			return retained;
		}
		finally
		{
			this.lock.unlock();
		}
	}

	/**
	 * @see net.sf.hajdbc.balancer.Balancer#clear()
	 */
	@Override
	public void clear()
	{
		this.lock.lock();
		
		try
		{
			if (!this.databaseSet.isEmpty())
			{
				this.databaseSet = Collections.emptySortedSet();
				
				this.cleared();
			}
		}
		finally
		{
			this.lock.unlock();
		}
	}
	
	/**
	 * Called when the set was cleared.
	 */
	protected abstract void cleared();
}