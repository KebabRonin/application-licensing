/*
 * See the NOTICE file distributed with this work for additional
 * information regarding copyright ownership.
 *
 * This is free software; you can redistribute it and/or modify it
 * under the terms of the GNU Lesser General Public License as
 * published by the Free Software Foundation; either version 2.1 of
 * the License, or (at your option) any later version.
 *
 * This software is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE. See the GNU
 * Lesser General Public License for more details.
 *
 * You should have received a copy of the GNU Lesser General Public
 * License along with this software; if not, write to the Free
 * Software Foundation, Inc., 51 Franklin St, Fifth Floor, Boston, MA
 * 02110-1301 USA, or see the FSF site: http://www.fsf.org.
 */
package com.xwiki.licensing.internal;

import java.util.Arrays;
import java.util.List;
import java.util.stream.Collectors;

import javax.inject.Inject;
import javax.inject.Named;
import javax.inject.Singleton;

import org.apache.solr.client.solrj.response.QueryResponse;
import org.apache.solr.common.SolrDocument;
import org.apache.solr.common.SolrDocumentList;
import org.slf4j.Logger;
import org.xwiki.bridge.event.DocumentCreatedEvent;
import org.xwiki.bridge.event.DocumentDeletedEvent;
import org.xwiki.bridge.event.DocumentUpdatedEvent;
import org.xwiki.component.annotation.Component;
import org.xwiki.model.reference.DocumentReference;
import org.xwiki.model.reference.DocumentReferenceResolver;
import org.xwiki.model.reference.LocalDocumentReference;
import org.xwiki.observation.AbstractEventListener;
import org.xwiki.observation.event.Event;
import org.xwiki.query.Query;
import org.xwiki.query.QueryException;
import org.xwiki.query.QueryFilter;
import org.xwiki.query.QueryManager;
import org.xwiki.wiki.descriptor.WikiDescriptorManager;

import com.xpn.xwiki.doc.XWikiDocument;
import com.xpn.xwiki.objects.BaseObject;

/**
 * Component used to count the existing active users.
 * 
 * @version $Id$
 * @since 1.6
 */
@Component(roles = UserCounter.class)
@Singleton
public class UserCounter
{
    @Inject
    private Logger logger;

    @Inject
    private WikiDescriptorManager wikiDescriptorManager;

    @Inject
    private QueryManager queryManager;

    @Inject
    @Named("count")
    private QueryFilter countFilter;

    private Long cachedUserCount;

    private List<DocumentReference> cachedOldestUsers;

    @Inject
    private DocumentReferenceResolver<SolrDocument> solrDocumentReferenceResolver;

    /**
     * Event listener that invalidates the cached user count when an user is added, deleted or the active property's
     * value is changed.
     * 
     * @version $Id$
     * @since 1.6
     */
    @Component
    @Singleton
    @Named(UserListener.HINT)
    public static class UserListener extends AbstractEventListener
    {
        /**
         * The event listener component hint.
         */
        public static final String HINT = "com.xwiki.licensing.internal.UserCounter.UserListener";

        protected static final String ACTIVE = "active";

        protected static final LocalDocumentReference USER_CLASS = new LocalDocumentReference("XWiki", "XWikiUsers");

        @Inject
        private UserCounter userCounter;

        /**
         * Default constructor.
         */
        public UserListener()
        {
            super(HINT,
                Arrays.asList(new DocumentCreatedEvent(), new DocumentUpdatedEvent(), new DocumentDeletedEvent()));
        }

        @Override
        public void onEvent(Event event, Object source, Object data)
        {
            XWikiDocument newDocument = (XWikiDocument) source;
            XWikiDocument oldDocument = newDocument.getOriginalDocument();

            BaseObject newObject = newDocument.getXObject(USER_CLASS);
            BaseObject oldObject = oldDocument.getXObject(USER_CLASS);

            boolean newDocumentIsUser = newObject != null;
            boolean oldDocumentIsUser = oldObject != null;

            // Set defaults to -1 to avoid nulls.
            int newActive = newDocumentIsUser ? newObject.getIntValue(ACTIVE) : -1;
            int oldActive = oldDocumentIsUser ? oldObject.getIntValue(ACTIVE) : -1;

            if (newDocumentIsUser != oldDocumentIsUser || newActive != oldActive) {
                // The user object is either added/removed or set to active/inactive. Invalidate the cached user count.
                this.userCounter.cachedUserCount = null;
                this.userCounter.cachedOldestUsers = null;
            }
        }
    }

    /**
     * Get the users sorted by creation date.
     *
     * @param limit the number of users to return
     * @return the users, sorted by creation date.
     */
    public final List<DocumentReference> getOldestUsers(int limit) throws Exception
    {
        if (cachedOldestUsers == null || cachedOldestUsers.size() < limit) {
            try {
                Query query = getUserQuery();
                if (limit >= 0) {
                    query.setLimit(limit);
                }
                SolrDocumentList activeUsers = ((QueryResponse) query.execute().get(0)).getResults();
                this.cachedUserCount = activeUsers.getNumFound();
                this.cachedOldestUsers =
                    activeUsers.stream().map(solrDocumentReferenceResolver::resolve).collect(Collectors.toList());
            } catch (QueryException e) {
                throw new Exception("Failed to get the oldest users for a license.", e);
            }
        }

        return cachedOldestUsers.subList(0, limit);
    }

    /**
     * Counts the existing active users.
     * 
     * @return the user count
     * @throws Exception if we fail to count the users
     */
    public long getUserCount() throws Exception
    {
        if (cachedUserCount == null) {
            try {
                Query query = getUserQuery().setLimit(1);
                long userCount = ((QueryResponse) query.execute().get(0)).getResults().getNumFound();
                this.logger.debug("User count is [{}].", userCount);
                this.cachedUserCount = userCount;
            } catch (QueryException e) {
                throw new Exception("Failed to count the users.", e);
            }
        }

        return cachedUserCount;
    }

    private Query getUserQuery() throws QueryException
    {
        Query query =
            queryManager.createQuery("object:XWiki.XWikiUsers AND property.XWiki.XWikiUsers.active:true", "solr");
        query.bindValue("fq", "type:DOCUMENT").bindValue("sort", "creationdate asc");
        return query;
    }
}
