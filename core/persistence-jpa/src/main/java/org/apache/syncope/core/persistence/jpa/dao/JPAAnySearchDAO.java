/*
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 *   http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the License is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 * KIND, either express or implied.  See the License for the
 * specific language governing permissions and limitations
 * under the License.
 */
package org.apache.syncope.core.persistence.jpa.dao;

import java.lang.annotation.Annotation;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Date;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import javax.persistence.Entity;
import javax.persistence.Query;
import javax.persistence.TemporalType;
import javax.validation.ValidationException;
import javax.validation.constraints.Max;
import javax.validation.constraints.Min;
import org.apache.commons.collections4.CollectionUtils;
import org.apache.commons.lang3.ClassUtils;
import org.apache.commons.lang3.SerializationUtils;
import org.apache.commons.lang3.StringUtils;
import org.apache.syncope.common.lib.SyncopeConstants;
import org.apache.syncope.common.lib.types.AnyTypeKind;
import org.apache.syncope.common.lib.types.AttrSchemaType;
import org.apache.syncope.core.provisioning.api.utils.RealmUtils;
import org.apache.syncope.core.provisioning.api.utils.EntityUtils;
import org.apache.syncope.core.persistence.api.dao.AnyObjectDAO;
import org.apache.syncope.core.persistence.api.dao.PlainSchemaDAO;
import org.apache.syncope.core.persistence.api.dao.GroupDAO;
import org.apache.syncope.core.persistence.api.dao.RealmDAO;
import org.apache.syncope.core.persistence.api.dao.AnySearchDAO;
import org.apache.syncope.core.persistence.api.dao.UserDAO;
import org.apache.syncope.core.persistence.api.dao.search.AttributeCond;
import org.apache.syncope.core.persistence.api.dao.search.MembershipCond;
import org.apache.syncope.core.persistence.api.dao.search.OrderByClause;
import org.apache.syncope.core.persistence.api.dao.search.ResourceCond;
import org.apache.syncope.core.persistence.api.dao.search.RoleCond;
import org.apache.syncope.core.persistence.api.dao.search.SearchCond;
import org.apache.syncope.core.persistence.api.dao.search.AnyCond;
import org.apache.syncope.core.persistence.api.dao.search.AnyTypeCond;
import org.apache.syncope.core.persistence.api.dao.search.AssignableCond;
import org.apache.syncope.core.persistence.api.dao.search.MemberCond;
import org.apache.syncope.core.persistence.api.dao.search.RelationshipCond;
import org.apache.syncope.core.persistence.api.dao.search.RelationshipTypeCond;
import org.apache.syncope.core.persistence.api.entity.Any;
import org.apache.syncope.core.persistence.api.entity.AnyUtils;
import org.apache.syncope.core.persistence.api.entity.AnyUtilsFactory;
import org.apache.syncope.core.persistence.api.entity.PlainAttrValue;
import org.apache.syncope.core.persistence.api.entity.PlainSchema;
import org.apache.syncope.core.persistence.api.entity.Realm;
import org.apache.syncope.core.persistence.api.entity.anyobject.AnyObject;
import org.apache.syncope.core.persistence.api.entity.group.Group;
import org.apache.syncope.core.persistence.jpa.entity.JPAPlainSchema;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.ReflectionUtils;

@Repository
public class JPAAnySearchDAO extends AbstractDAO<Any<?>> implements AnySearchDAO {

    private static final String EMPTY_QUERY = "SELECT any_id FROM user_search_attr WHERE 1=2";

    @Autowired
    private RealmDAO realmDAO;

    @Autowired
    private AnyObjectDAO anyObjectDAO;

    @Autowired
    private UserDAO userDAO;

    @Autowired
    private GroupDAO groupDAO;

    @Autowired
    private PlainSchemaDAO schemaDAO;

    @Autowired
    private AnyUtilsFactory anyUtilsFactory;

    private String getAdminRealmsFilter(
            final Set<String> adminRealms,
            final SearchSupport svs,
            final List<Object> parameters) {

        Set<String> realmKeys = new HashSet<>();
        for (String realmPath : RealmUtils.normalize(adminRealms)) {
            Realm realm = realmDAO.findByFullPath(realmPath);
            if (realm == null) {
                LOG.warn("Ignoring invalid realm {}", realmPath);
            } else {
                CollectionUtils.collect(
                        realmDAO.findDescendants(realm), EntityUtils.<Realm>keyTransformer(), realmKeys);
            }
        }

        StringBuilder adminRealmFilter = new StringBuilder().
                append("SELECT any_id FROM ").append(svs.field().name).
                append(" WHERE realm_id IN (SELECT id AS realm_id FROM Realm");

        boolean firstRealm = true;
        for (String realmKey : realmKeys) {
            if (firstRealm) {
                adminRealmFilter.append(" WHERE");
                firstRealm = false;
            } else {
                adminRealmFilter.append(" OR");
            }
            adminRealmFilter.append(" id=?").append(setParameter(parameters, realmKey));
        }

        adminRealmFilter.append(')');

        return adminRealmFilter.toString();
    }

    @Override
    public int count(final Set<String> adminRealms, final SearchCond searchCondition, final AnyTypeKind typeKind) {
        List<Object> parameters = Collections.synchronizedList(new ArrayList<>());

        // 1. get the query string from the search condition
        SearchSupport svs = new SearchSupport(typeKind);
        StringBuilder queryString = getQuery(searchCondition, parameters, svs);

        // 2. take into account administrative realms
        queryString.insert(0, "SELECT u.any_id FROM (");
        queryString.append(") u WHERE any_id IN (");
        queryString.append(getAdminRealmsFilter(adminRealms, svs, parameters)).append(')');

        // 3. prepare the COUNT query
        queryString.insert(0, "SELECT COUNT(any_id) FROM (");
        queryString.append(") count_any_id");

        Query countQuery = entityManager().createNativeQuery(queryString.toString());
        fillWithParameters(countQuery, parameters);

        return ((Number) countQuery.getSingleResult()).intValue();
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW, readOnly = true)
    @Override
    public <T extends Any<?>> List<T> searchAssignable(final String realmFullPath, final AnyTypeKind kind) {
        AssignableCond assignableCond = new AssignableCond();
        assignableCond.setRealmFullPath(realmFullPath);
        return search(SearchCond.getLeafCond(assignableCond), kind);
    }

    @Override
    public <T extends Any<?>> List<T> search(final SearchCond searchCondition, final AnyTypeKind typeKind) {
        return search(searchCondition, Collections.<OrderByClause>emptyList(), typeKind);
    }

    @Override
    public <T extends Any<?>> List<T> search(
            final SearchCond searchCondition, final List<OrderByClause> orderBy, final AnyTypeKind typeKind) {

        return search(SyncopeConstants.FULL_ADMIN_REALMS, searchCondition, -1, -1, orderBy, typeKind);
    }

    @Override
    public <T extends Any<?>> List<T> search(
            final Set<String> adminRealms, final SearchCond searchCondition, final int page, final int itemsPerPage,
            final List<OrderByClause> orderBy, final AnyTypeKind typeKind) {

        List<T> result = Collections.<T>emptyList();

        if (adminRealms != null && !adminRealms.isEmpty()) {
            LOG.debug("Search condition:\n{}", searchCondition);

            if (searchCondition != null && searchCondition.isValid()) {
                try {
                    result = doSearch(adminRealms, searchCondition, page, itemsPerPage, orderBy, typeKind);
                } catch (Exception e) {
                    LOG.error("While searching for {}", typeKind, e);
                }
            } else {
                LOG.error("Invalid search condition:\n{}", searchCondition);
            }
        }

        return result;
    }

    @Override
    public <T extends Any<?>> boolean matches(
            final T any, final SearchCond searchCondition, final AnyTypeKind typeKind) {

        List<Object> parameters = Collections.synchronizedList(new ArrayList<>());

        // 1. get the query string from the search condition
        SearchSupport svs = new SearchSupport(typeKind);
        StringBuilder queryString = getQuery(searchCondition, parameters, svs);

        boolean matches;
        if (queryString.length() == 0) {
            // Could be empty: got into a group search with a single membership condition ...
            matches = false;
        } else {
            // 2. take into account the passed user
            queryString.insert(0, "SELECT u.any_id FROM (");
            queryString.append(") u WHERE any_id=?").append(setParameter(parameters, any.getKey()));

            // 3. prepare the search query
            Query query = entityManager().createNativeQuery(queryString.toString());

            // 4. populate the search query with parameter values
            fillWithParameters(query, parameters);

            // 5. executes query
            matches = !query.getResultList().isEmpty();
        }

        return matches;
    }

    private int setParameter(final List<Object> parameters, final Object parameter) {
        int key;
        synchronized (parameters) {
            parameters.add(parameter);
            key = parameters.size();
        }

        return key;
    }

    private void fillWithParameters(final Query query, final List<Object> parameters) {
        for (int i = 0; i < parameters.size(); i++) {
            if (parameters.get(i) instanceof Date) {
                query.setParameter(i + 1, (Date) parameters.get(i), TemporalType.TIMESTAMP);
            } else if (parameters.get(i) instanceof Boolean) {
                query.setParameter(i + 1, ((Boolean) parameters.get(i))
                        ? 1
                        : 0);
            } else {
                query.setParameter(i + 1, parameters.get(i));
            }
        }
    }

    private StringBuilder buildSelect(final OrderBySupport orderBySupport) {
        final StringBuilder select = new StringBuilder("SELECT u.any_id");

        for (OrderBySupport.Item obs : orderBySupport.items) {
            select.append(',').append(obs.select);
        }
        select.append(" FROM ");

        return select;
    }

    private StringBuilder buildWhere(final OrderBySupport orderBySupport, final AnyTypeKind typeKind) {
        SearchSupport svs = new SearchSupport(typeKind);
        StringBuilder where = new StringBuilder(" u");
        for (SearchSupport.SearchView searchView : orderBySupport.views) {
            where.append(',');
            if (searchView.name.equals(svs.attr().name)) {
                where.append(" (SELECT * FROM ").append(searchView.name).append(" UNION ").
                        append("SELECT * FROM ").append(svs.nullAttr().name).append(')');
            } else {
                where.append(searchView.name);
            }
            where.append(' ').append(searchView.alias);
        }
        where.append(" WHERE ");
        for (SearchSupport.SearchView searchView : orderBySupport.views) {
            where.append("u.any_id=").append(searchView.alias).append(".any_id AND ");
        }

        for (OrderBySupport.Item obs : orderBySupport.items) {
            if (StringUtils.isNotBlank(obs.where)) {
                where.append(obs.where).append(" AND ");
            }
        }
        where.append("u.any_id IN (");

        return where;
    }

    private StringBuilder buildOrderBy(final OrderBySupport orderBySupport) {
        StringBuilder orderBy = new StringBuilder();

        for (OrderBySupport.Item obs : orderBySupport.items) {
            orderBy.append(obs.orderBy).append(',');
        }
        if (!orderBySupport.items.isEmpty()) {
            orderBy.insert(0, " ORDER BY ");
            orderBy.deleteCharAt(orderBy.length() - 1);
        }

        return orderBy;
    }

    private OrderBySupport parseOrderBy(final AnyTypeKind type, final SearchSupport svs,
            final List<OrderByClause> orderByClauses) {

        final AnyUtils attrUtils = anyUtilsFactory.getInstance(type);

        OrderBySupport orderBySupport = new OrderBySupport();

        for (OrderByClause clause : orderByClauses) {
            OrderBySupport.Item obs = new OrderBySupport.Item();

            // Manage difference among external key attribute and internal JPA @Id
            String fieldName = "key".equals(clause.getField()) ? "id" : clause.getField();

            Field anyField = ReflectionUtils.findField(attrUtils.anyClass(), fieldName);
            if (anyField == null) {
                PlainSchema schema = schemaDAO.find(fieldName);
                if (schema != null) {
                    if (schema.isUniqueConstraint()) {
                        orderBySupport.views.add(svs.uniqueAttr());

                        obs.select = new StringBuilder().
                                append(svs.uniqueAttr().alias).append('.').append(svs.fieldName(schema.getType())).
                                append(" AS ").append(fieldName).toString();
                        obs.where = new StringBuilder().
                                append(svs.uniqueAttr().alias).
                                append(".schema_id='").append(fieldName).append("'").toString();
                        obs.orderBy = fieldName + " " + clause.getDirection().name();
                    } else {
                        orderBySupport.views.add(svs.attr());

                        obs.select = new StringBuilder().
                                append(svs.attr().alias).append('.').append(svs.fieldName(schema.getType())).
                                append(" AS ").append(fieldName).toString();
                        obs.where = new StringBuilder().
                                append(svs.attr().alias).
                                append(".schema_id='").append(fieldName).append("'").toString();
                        obs.orderBy = fieldName + " " + clause.getDirection().name();
                    }
                }
            } else {
                orderBySupport.views.add(svs.field());

                obs.select = svs.field().alias + "." + fieldName;
                obs.where = StringUtils.EMPTY;
                obs.orderBy = svs.field().alias + "." + fieldName + " " + clause.getDirection().name();
            }

            if (obs.isEmpty()) {
                LOG.warn("Cannot build any valid clause from {}", clause);
            } else {
                orderBySupport.items.add(obs);
            }
        }

        return orderBySupport;
    }

    @SuppressWarnings("unchecked")
    private <T extends Any<?>> List<T> doSearch(final Set<String> adminRealms,
            final SearchCond nodeCond, final int page, final int itemsPerPage, final List<OrderByClause> orderBy,
            final AnyTypeKind typeKind) {

        List<Object> parameters = Collections.synchronizedList(new ArrayList<>());

        // 1. get the query string from the search condition
        SearchSupport svs = new SearchSupport(typeKind);
        StringBuilder queryString = getQuery(nodeCond, parameters, svs);

        // 2. take into account administrative groups and ordering
        OrderBySupport orderBySupport = parseOrderBy(typeKind, svs, orderBy);
        if (queryString.charAt(0) == '(') {
            queryString.insert(0, buildSelect(orderBySupport));
            queryString.append(buildWhere(orderBySupport, typeKind));
        } else {
            queryString.insert(0, buildSelect(orderBySupport).append('('));
            queryString.append(')').append(buildWhere(orderBySupport, typeKind));
        }
        queryString.
                append(getAdminRealmsFilter(adminRealms, svs, parameters)).append(')').
                append(buildOrderBy(orderBySupport));

        // 3. prepare the search query
        Query query = entityManager().createNativeQuery(queryString.toString());

        // 4. page starts from 1, while setFirtResult() starts from 0
        query.setFirstResult(itemsPerPage * (page <= 0 ? 0 : page - 1));

        if (itemsPerPage >= 0) {
            query.setMaxResults(itemsPerPage);
        }

        // 5. populate the search query with parameter values
        fillWithParameters(query, parameters);

        // 6. Prepare the result (avoiding duplicates)
        List<T> result = new ArrayList<>();

        for (Object anyKey : query.getResultList()) {
            String actualKey = anyKey instanceof Object[]
                    ? (String) ((Object[]) anyKey)[0]
                    : ((String) anyKey);

            T any = typeKind == AnyTypeKind.USER
                    ? (T) userDAO.find(actualKey)
                    : typeKind == AnyTypeKind.GROUP
                            ? (T) groupDAO.find(actualKey)
                            : (T) anyObjectDAO.find(actualKey);
            if (any == null) {
                LOG.error("Could not find {} with id {}, even though returned by the native query",
                        typeKind, actualKey);
            } else if (!result.contains(any)) {
                result.add(any);
            }
        }

        return result;
    }

    private StringBuilder getQuery(final SearchCond nodeCond, final List<Object> parameters, final SearchSupport svs) {
        StringBuilder query = new StringBuilder();

        switch (nodeCond.getType()) {

            case LEAF:
            case NOT_LEAF:
                if (nodeCond.getAnyTypeCond() != null && AnyTypeKind.ANY_OBJECT == svs.anyTypeKind()) {
                    query.append(getQuery(nodeCond.getAnyTypeCond(),
                            nodeCond.getType() == SearchCond.Type.NOT_LEAF, parameters, svs));
                } else if (nodeCond.getRelationshipTypeCond() != null
                        && (AnyTypeKind.USER == svs.anyTypeKind() || AnyTypeKind.ANY_OBJECT == svs.anyTypeKind())) {

                    query.append(getQuery(nodeCond.getRelationshipTypeCond(),
                            nodeCond.getType() == SearchCond.Type.NOT_LEAF, parameters, svs));
                } else if (nodeCond.getRelationshipCond() != null
                        && (AnyTypeKind.USER == svs.anyTypeKind() || AnyTypeKind.ANY_OBJECT == svs.anyTypeKind())) {

                    query.append(getQuery(nodeCond.getRelationshipCond(),
                            nodeCond.getType() == SearchCond.Type.NOT_LEAF, parameters, svs));
                } else if (nodeCond.getMembershipCond() != null
                        && (AnyTypeKind.USER == svs.anyTypeKind() || AnyTypeKind.ANY_OBJECT == svs.anyTypeKind())) {

                    query.append(getQuery(nodeCond.getMembershipCond(),
                            nodeCond.getType() == SearchCond.Type.NOT_LEAF, parameters, svs));
                } else if (nodeCond.getAssignableCond() != null) {
                    query.append(getQuery(nodeCond.getAssignableCond(), parameters, svs));
                } else if (nodeCond.getRoleCond() != null && AnyTypeKind.USER == svs.anyTypeKind()) {
                    query.append(getQuery(nodeCond.getRoleCond(),
                            nodeCond.getType() == SearchCond.Type.NOT_LEAF, parameters, svs));
                } else if (nodeCond.getMemberCond() != null && AnyTypeKind.GROUP == svs.anyTypeKind()) {
                    query.append(getQuery(nodeCond.getMemberCond(),
                            nodeCond.getType() == SearchCond.Type.NOT_LEAF, parameters, svs));
                } else if (nodeCond.getResourceCond() != null) {
                    query.append(getQuery(nodeCond.getResourceCond(),
                            nodeCond.getType() == SearchCond.Type.NOT_LEAF, parameters, svs));
                } else if (nodeCond.getAttributeCond() != null) {
                    query.append(getQuery(nodeCond.getAttributeCond(),
                            nodeCond.getType() == SearchCond.Type.NOT_LEAF, parameters, svs));
                } else if (nodeCond.getAnyCond() != null) {
                    query.append(getQuery(nodeCond.getAnyCond(),
                            nodeCond.getType() == SearchCond.Type.NOT_LEAF, parameters, svs));
                }
                break;

            case AND:
                String andSubQuery = getQuery(nodeCond.getLeftNodeCond(), parameters, svs).toString();
                // Add extra parentheses
                andSubQuery = andSubQuery.replaceFirst("WHERE ", "WHERE (");
                query.append(andSubQuery).
                        append(" AND any_id IN ( ").
                        append(getQuery(nodeCond.getRightNodeCond(), parameters, svs)).
                        append("))");
                break;

            case OR:
                String orSubQuery = getQuery(nodeCond.getLeftNodeCond(), parameters, svs).toString();
                // Add extra parentheses
                orSubQuery = orSubQuery.replaceFirst("WHERE ", "WHERE (");
                query.append(orSubQuery).
                        append(" OR any_id IN ( ").
                        append(getQuery(nodeCond.getRightNodeCond(), parameters, svs)).
                        append("))");
                break;

            default:
        }

        return query;
    }

    private String getQuery(final AnyTypeCond cond, final boolean not, final List<Object> parameters,
            final SearchSupport svs) {

        StringBuilder query = new StringBuilder("SELECT DISTINCT any_id FROM ").
                append(svs.field().name).append(" WHERE type_id");

        if (not) {
            query.append("<>");
        } else {
            query.append('=');
        }

        query.append('?').append(setParameter(parameters, cond.getAnyTypeKey()));

        return query.toString();
    }

    private String getQuery(final RelationshipTypeCond cond, final boolean not, final List<Object> parameters,
            final SearchSupport svs) {

        StringBuilder query = new StringBuilder("SELECT DISTINCT any_id FROM ").
                append(svs.field().name).append(" WHERE ");

        if (not) {
            query.append("any_id NOT IN (");
        } else {
            query.append("any_id IN (");
        }

        query.append("SELECT any_id ").append("FROM ").
                append(svs.relationship().name).
                append(" WHERE type=?").append(setParameter(parameters, cond.getRelationshipTypeKey())).
                append(" UNION SELECT right_any_id AS any_id FROM ").
                append(svs.relationship().name).
                append(" WHERE type=?").append(setParameter(parameters, cond.getRelationshipTypeKey())).
                append(')');

        return query.toString();
    }

    private String getQuery(final RelationshipCond cond, final boolean not, final List<Object> parameters,
            final SearchSupport svs) {

        String rightAnyObjectKey;
        if (SyncopeConstants.UUID_PATTERN.matcher(cond.getAnyObject()).matches()) {
            rightAnyObjectKey = cond.getAnyObject();
        } else {
            AnyObject anyObject = anyObjectDAO.findByName(cond.getAnyObject());
            rightAnyObjectKey = anyObject == null ? null : anyObject.getKey();
        }
        if (rightAnyObjectKey == null) {
            return EMPTY_QUERY;
        }

        StringBuilder query = new StringBuilder("SELECT DISTINCT any_id FROM ").
                append(svs.field().name).append(" WHERE ");

        if (not) {
            query.append("any_id NOT IN (");
        } else {
            query.append("any_id IN (");
        }

        query.append("SELECT DISTINCT any_id FROM ").
                append(svs.relationship().name).append(" WHERE ").
                append("right_any_id=?").append(setParameter(parameters, rightAnyObjectKey)).
                append(')');

        return query.toString();
    }

    private String getQuery(final MembershipCond cond, final boolean not, final List<Object> parameters,
            final SearchSupport svs) {

        String groupKey;
        if (SyncopeConstants.UUID_PATTERN.matcher(cond.getGroup()).matches()) {
            groupKey = cond.getGroup();
        } else {
            Group group = groupDAO.findByName(cond.getGroup());
            groupKey = group == null ? null : group.getKey();
        }
        if (groupKey == null) {
            return EMPTY_QUERY;
        }

        StringBuilder query = new StringBuilder("SELECT DISTINCT any_id FROM ").
                append(svs.field().name).append(" WHERE ");

        if (not) {
            query.append("any_id NOT IN (");
        } else {
            query.append("any_id IN (");
        }

        query.append("SELECT DISTINCT any_id FROM ").
                append(svs.membership().name).append(" WHERE ").
                append("group_id=?").append(setParameter(parameters, groupKey)).
                append(')');

        if (not) {
            query.append("AND any_id NOT IN (");
        } else {
            query.append("OR any_id IN (");
        }

        query.append("SELECT DISTINCT any_id FROM ").
                append(svs.dyngroupmembership().name).append(" WHERE ").
                append("group_id=?").append(setParameter(parameters, groupKey)).
                append(')');

        return query.toString();
    }

    private String getQuery(final RoleCond cond, final boolean not, final List<Object> parameters,
            final SearchSupport svs) {

        StringBuilder query = new StringBuilder("SELECT DISTINCT any_id FROM ").
                append(svs.field().name).append(" WHERE ");

        if (not) {
            query.append("any_id NOT IN (");
        } else {
            query.append("any_id IN (");
        }

        query.append("SELECT DISTINCT any_id FROM ").
                append(svs.role().name).append(" WHERE ").
                append("role_id=?").append(setParameter(parameters, cond.getRoleKey())).
                append(')');

        if (not) {
            query.append("AND any_id NOT IN (");
        } else {
            query.append("OR any_id IN (");
        }

        query.append("SELECT DISTINCT any_id FROM ").
                append(svs.dynrolemembership().name).append(" WHERE ").
                append("role_id=?").append(setParameter(parameters, cond.getRoleKey())).
                append(')');

        return query.toString();
    }

    private String getQuery(final ResourceCond cond, final boolean not, final List<Object> parameters,
            final SearchSupport svs) {

        StringBuilder query = new StringBuilder("SELECT DISTINCT any_id FROM ").
                append(svs.field().name).append(" WHERE ");

        if (not) {
            query.append("any_id NOT IN (");
        } else {
            query.append("any_id IN (");
        }

        query.append("SELECT DISTINCT any_id FROM ").
                append(svs.resource().name).
                append(" WHERE resource_id=?").
                append(setParameter(parameters, cond.getResourceKey()));

        if (svs.anyTypeKind() == AnyTypeKind.USER) {
            query.append(" UNION SELECT DISTINCT any_id FROM ").
                    append(svs.groupResource().name).
                    append(" WHERE resource_id=?").
                    append(setParameter(parameters, cond.getResourceKey()));
        }

        query.append(')');

        return query.toString();
    }

    private String getQuery(final AssignableCond cond, final List<Object> parameters, final SearchSupport svs) {
        Realm realm = realmDAO.findByFullPath(cond.getRealmFullPath());
        if (realm == null) {
            return EMPTY_QUERY;
        }

        StringBuilder query = new StringBuilder("SELECT DISTINCT any_id FROM ").
                append(svs.field().name).append(" WHERE (");
        if (cond.isFromGroup()) {
            for (Realm current = realm; current.getParent() != null; current = current.getParent()) {
                query.append("realm_id=?").append(setParameter(parameters, current.getKey())).append(" OR ");
            }
            query.append("realm_id=?").append(setParameter(parameters, realmDAO.getRoot().getKey()));
        } else {
            for (Realm current : realmDAO.findDescendants(realm)) {
                query.append("realm_id=?").append(setParameter(parameters, current.getKey())).append(" OR ");
            }
            query.setLength(query.length() - 4);
        }
        query.append(')');

        return query.toString();
    }

    private String getQuery(final MemberCond cond, final boolean not, final List<Object> parameters,
            final SearchSupport svs) {

        String memberKey;
        if (SyncopeConstants.UUID_PATTERN.matcher(cond.getMember()).matches()) {
            memberKey = cond.getMember();
        } else {
            Any member = userDAO.findByUsername(cond.getMember());
            if (member == null) {
                member = anyObjectDAO.findByName(cond.getMember());
            }
            memberKey = member == null ? null : member.getKey();
        }
        if (memberKey == null) {
            return EMPTY_QUERY;
        }

        StringBuilder query = new StringBuilder("SELECT DISTINCT any_id FROM ").
                append(svs.field().name).append(" WHERE ");

        if (not) {
            query.append("any_id NOT IN (");
        } else {
            query.append("any_id IN (");
        }

        query.append("SELECT DISTINCT group_id AS any_id FROM ").
                append(new SearchSupport(AnyTypeKind.USER).membership().name).append(" WHERE ").
                append("any_id=?").append(setParameter(parameters, memberKey)).
                append(')');

        if (not) {
            query.append(" AND any_id NOT IN (");
        } else {
            query.append(" OR any_id IN (");
        }

        query.append("SELECT DISTINCT group_id AS any_id FROM ").
                append(new SearchSupport(AnyTypeKind.ANY_OBJECT).membership().name).append(" WHERE ").
                append("any_id=?").append(setParameter(parameters, memberKey)).
                append(')');

        return query.toString();
    }

    private void fillAttributeQuery(final StringBuilder query, final PlainAttrValue attrValue,
            final PlainSchema schema, final AttributeCond cond, final boolean not,
            final List<Object> parameters, final SearchSupport svs) {

        String column = (cond instanceof AnyCond)
                ? cond.getSchema()
                : "' AND " + svs.fieldName(schema.getType());

        switch (cond.getType()) {

            case ISNULL:
                query.append(column).append(not
                        ? " IS NOT NULL"
                        : " IS NULL");
                break;

            case ISNOTNULL:
                query.append(column).append(not
                        ? " IS NULL"
                        : " IS NOT NULL");
                break;

            case LIKE:
                if (schema.getType() == AttrSchemaType.String || schema.getType() == AttrSchemaType.Enum) {
                    query.append(column);
                    if (not) {
                        query.append(" NOT ");
                    }
                    query.append(" LIKE ?").append(setParameter(parameters, cond.getExpression()));
                } else {
                    if (!(cond instanceof AnyCond)) {
                        query.append("' AND");
                    }
                    query.append(" 1=2");
                    LOG.error("LIKE is only compatible with string or enum schemas");
                }
                break;

            case EQ:
                query.append(column);
                if (not) {
                    query.append("<>");
                } else {
                    query.append('=');
                }
                query.append('?').append(setParameter(parameters, attrValue.getValue()));
                break;

            case GE:
                query.append(column);
                if (not) {
                    query.append('<');
                } else {
                    query.append(">=");
                }
                query.append('?').append(setParameter(parameters, attrValue.getValue()));
                break;

            case GT:
                query.append(column);
                if (not) {
                    query.append("<=");
                } else {
                    query.append('>');
                }
                query.append('?').append(setParameter(parameters, attrValue.getValue()));
                break;

            case LE:
                query.append(column);
                if (not) {
                    query.append('>');
                } else {
                    query.append("<=");
                }
                query.append('?').append(setParameter(parameters, attrValue.getValue()));
                break;

            case LT:
                query.append(column);
                if (not) {
                    query.append(">=");
                } else {
                    query.append('<');
                }
                query.append('?').append(setParameter(parameters, attrValue.getValue()));
                break;

            default:
        }
    }

    private String getQuery(final AttributeCond cond, final boolean not, final List<Object> parameters,
            final SearchSupport svs) {

        AnyUtils attrUtils = anyUtilsFactory.getInstance(svs.anyTypeKind());

        PlainSchema schema = schemaDAO.find(cond.getSchema());
        if (schema == null) {
            LOG.warn("Ignoring invalid schema '{}'", cond.getSchema());
            return EMPTY_QUERY;
        }

        PlainAttrValue attrValue = attrUtils.newPlainAttrValue();
        try {
            if (cond.getType() != AttributeCond.Type.LIKE && cond.getType() != AttributeCond.Type.ISNULL
                    && cond.getType() != AttributeCond.Type.ISNOTNULL) {

                schema.getValidator().validate(cond.getExpression(), attrValue);
            }
        } catch (ValidationException e) {
            LOG.error("Could not validate expression '" + cond.getExpression() + "'", e);
            return EMPTY_QUERY;
        }

        StringBuilder query = new StringBuilder("SELECT DISTINCT any_id FROM ");
        switch (cond.getType()) {
            case ISNOTNULL:
                query.append(svs.field().name).
                        append(" WHERE any_id NOT IN (SELECT any_id FROM ").
                        append(svs.nullAttr().name).
                        append(" WHERE schema_id='").append(schema.getKey()).append("')");
                break;

            case ISNULL:
                query.append(svs.nullAttr().name).
                        append(" WHERE schema_id='").append(schema.getKey()).append("'");
                break;

            default:
                if (schema.isUniqueConstraint()) {
                    query.append(svs.uniqueAttr().name);
                } else {
                    query.append(svs.attr().name);
                }
                query.append(" WHERE schema_id='").append(schema.getKey());
                fillAttributeQuery(query, attrValue, schema, cond, not, parameters, svs);
        }

        return query.toString();
    }

    private String getQuery(final AnyCond cond, final boolean not, final List<Object> parameters,
            final SearchSupport svs) {

        AnyCond condClone = SerializationUtils.clone(cond);
        
        AnyUtils attrUtils = anyUtilsFactory.getInstance(svs.anyTypeKind());

        // Keeps track of difference between entity's getKey() and JPA @Id fields
        if ("key".equals(condClone.getSchema())) {
            condClone.setSchema("id");
        }

        Field anyField = ReflectionUtils.findField(attrUtils.anyClass(), condClone.getSchema());
        if (anyField == null) {
            LOG.warn("Ignoring invalid schema '{}'", condClone.getSchema());
            return EMPTY_QUERY;
        }

        PlainSchema schema = new JPAPlainSchema();
        schema.setKey(anyField.getName());
        for (AttrSchemaType attrSchemaType : AttrSchemaType.values()) {
            if (anyField.getType().isAssignableFrom(attrSchemaType.getType())) {
                schema.setType(attrSchemaType);
            }
        }

        // Deal with any Integer fields logically mapping to boolean values
        boolean foundBooleanMin = false;
        boolean foundBooleanMax = false;
        if (Integer.class.equals(anyField.getType())) {
            for (Annotation annotation : anyField.getAnnotations()) {
                if (Min.class.equals(annotation.annotationType())) {
                    foundBooleanMin = ((Min) annotation).value() == 0;
                } else if (Max.class.equals(annotation.annotationType())) {
                    foundBooleanMax = ((Max) annotation).value() == 1;
                }
            }
        }
        if (foundBooleanMin && foundBooleanMax) {
            schema.setType(AttrSchemaType.Boolean);
        }

        // Deal with any fields representing relationships to other entities
        if (anyField.getType().getAnnotation(Entity.class) != null) {
            Method relMethod = null;
            try {
                relMethod = ClassUtils.getPublicMethod(anyField.getType(), "getKey", new Class<?>[0]);
            } catch (Exception e) {
                LOG.error("Could not find {}#getKey", anyField.getType(), e);
            }

            if (relMethod != null && String.class.isAssignableFrom(relMethod.getReturnType())) {
                condClone.setSchema(condClone.getSchema() + "_id");
                schema.setType(AttrSchemaType.String);
            }
        }

        PlainAttrValue attrValue = attrUtils.newPlainAttrValue();
        if (condClone.getType() != AttributeCond.Type.LIKE
                && condClone.getType() != AttributeCond.Type.ISNULL
                && condClone.getType() != AttributeCond.Type.ISNOTNULL) {

            try {
                schema.getValidator().validate(condClone.getExpression(), attrValue);
            } catch (ValidationException e) {
                LOG.error("Could not validate expression '" + condClone.getExpression() + "'", e);
                return EMPTY_QUERY;
            }
        }

        StringBuilder query = new StringBuilder("SELECT DISTINCT any_id FROM ").
                append(svs.field().name).append(" WHERE ");

        fillAttributeQuery(query, attrValue, schema, condClone, not, parameters, svs);

        return query.toString();
    }
}
