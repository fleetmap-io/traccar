/*
 * Copyright 2017 Anton Tananaev (anton@traccar.org)
 * Copyright 2017 Andrey Kunitsyn (andrey@traccar.org)
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.traccar.api.resource;

import java.sql.SQLException;
import java.util.*;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import javax.ws.rs.Consumes;
import javax.ws.rs.DELETE;
import javax.ws.rs.POST;
import javax.ws.rs.Path;
import javax.ws.rs.Produces;
import javax.ws.rs.WebApplicationException;
import javax.ws.rs.core.MediaType;
import javax.ws.rs.core.Response;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.traccar.Context;
import org.traccar.api.BaseResource;
import org.traccar.helper.LogAction;
import org.traccar.model.Device;
import org.traccar.model.Permission;
import org.traccar.model.User;

@Path("permissions")
@Produces(MediaType.APPLICATION_JSON)
@Consumes(MediaType.APPLICATION_JSON)
public class PermissionsResource  extends BaseResource {

    private static final Logger LOGGER = LoggerFactory.getLogger(PermissionsResource.class);

    private void logPermissionChange(String operation, List<LinkedHashMap<String, Long>> entities)
            throws ClassNotFoundException {
        if (!entities.isEmpty()) {
            Permission firstPermission = new Permission(entities.get(0));
            List<Long> ownerIds = new ArrayList<>();
            List<Long> propertyIds = new ArrayList<>();
            for (LinkedHashMap<String, Long> entity : entities) {
                Permission permission = new Permission(entity);
                ownerIds.add(permission.getOwnerId());
                propertyIds.add(permission.getPropertyId());
            }
            LOGGER.error("Permission {} actor={} ownerType={} owners={} propertyType={} properties={} count={}",
                    operation, getUserId(), firstPermission.getOwnerClass().getSimpleName(), ownerIds,
                    firstPermission.getPropertyClass().getSimpleName(), propertyIds, entities.size());
        }
    }

    private void checkPermission(Permission permission, boolean link) {
        if (!link && permission.getOwnerClass().equals(User.class)
                && permission.getPropertyClass().equals(Device.class)) {
            if (getUserId() != permission.getOwnerId()) {
                Context.getPermissionsManager().checkUser(getUserId(), permission.getOwnerId());
            } else {
                Context.getPermissionsManager().checkAdmin(getUserId());
            }
        } else {
            Context.getPermissionsManager().checkPermission(
                    permission.getOwnerClass(), getUserId(), permission.getOwnerId());
        }
        Context.getPermissionsManager().checkPermission(
                permission.getPropertyClass(), getUserId(), permission.getPropertyId());
    }

    private void checkPermissionTypes(List<LinkedHashMap<String, Long>> entities) {
        Set<String> keys = null;
        for (LinkedHashMap<String, Long> entity: entities) {
            if (keys != null & !entity.keySet().equals(keys)) {
                throw new WebApplicationException(Response.status(Response.Status.BAD_REQUEST).build());
            }
            keys = entity.keySet();
        }
    }

    @Path("bulk")
    @POST
    public Response add(List<LinkedHashMap<String, Long>> entities) throws SQLException, ClassNotFoundException {
        Context.getPermissionsManager().checkReadonly(getUserId());
        checkPermissionTypes(entities);
        HashSet permissionTypes = new HashSet();
        for (LinkedHashMap<String, Long> entity: entities) {
            Permission permission = new Permission(entity);
            checkPermission(permission, true);
            permissionTypes.add(permission.getOwnerClass().getSimpleName() + permission.getPropertyClass().getSimpleName());
        }
        // permissions are not of same type, let's do one by one
        if (permissionTypes.size() != 1) {
            for (LinkedHashMap<String, Long> entity : entities) {
                Permission permission = new Permission(entity);
                Context.getDataManager().linkObject(permission.getOwnerClass(), permission.getOwnerId(),
                        permission.getPropertyClass(), permission.getPropertyId(), true);
                LogAction.link(getUserId(), permission.getOwnerClass(), permission.getOwnerId(),
                        permission.getPropertyClass(), permission.getPropertyId());
            }
        }
        // all permissions are of same type, we can use a bulk insert
        else {
            Permission permission = new Permission(entities.get(0));
            Stream<Permission> permissions = entities.stream().map(e -> {
                try {
                    return new Permission(e);
                } catch (ClassNotFoundException ex) {
                    throw new RuntimeException(ex);
                }
            });
            Context.getDataManager().linkObjects(permission.getOwnerClass(), permission.getPropertyClass(),
                    permissions.collect(Collectors.toList()), true);
        }
        if (!entities.isEmpty()) {
            logPermissionChange("link", entities);
            Context.getPermissionsManager().refreshPermissions(new Permission(entities.get(0)));
        }
        return Response.noContent().build();
    }

    @POST
    public Response add(LinkedHashMap<String, Long> entity) throws SQLException, ClassNotFoundException {
        return add(Collections.singletonList(entity));
    }

    @DELETE
    @Path("bulk")
    public Response remove(List<LinkedHashMap<String, Long>> entities) throws SQLException, ClassNotFoundException {
        Context.getPermissionsManager().checkReadonly(getUserId());
        checkPermissionTypes(entities);
        HashSet permissionTypes = new HashSet();
        for (LinkedHashMap<String, Long> entity: entities) {
            Permission permission = new Permission(entity);
            checkPermission(permission, false);
            permissionTypes.add(permission.getOwnerClass().getSimpleName() + permission.getPropertyClass().getSimpleName());
        }
        // different permissions
        if (permissionTypes.size() != 1) {
            for (LinkedHashMap<String, Long> entity : entities) {
                Permission permission = new Permission(entity);
                Context.getDataManager().linkObject(permission.getOwnerClass(), permission.getOwnerId(),
                        permission.getPropertyClass(), permission.getPropertyId(), false);
                LogAction.unlink(getUserId(), permission.getOwnerClass(), permission.getOwnerId(),
                        permission.getPropertyClass(), permission.getPropertyId());
            }
        }
        // all permissions are of same type, we can use a bulk insert
        else {
            Permission permission = new Permission(entities.get(0));
            Stream<Permission> permissions = entities.stream().map(e -> {
                try {
                    return new Permission(e);
                } catch (ClassNotFoundException ex) {
                    throw new RuntimeException(ex);
                }
            });
            Context.getDataManager().linkObjects(permission.getOwnerClass(), permission.getPropertyClass(),
                    permissions.collect(Collectors.toList()), false);
        }

        if (!entities.isEmpty()) {
            logPermissionChange("unlink", entities);
            Context.getPermissionsManager().refreshPermissions(new Permission(entities.get(0)));
        }
        return Response.noContent().build();
    }

    @DELETE
    public Response remove(LinkedHashMap<String, Long> entity) throws SQLException, ClassNotFoundException {
        return remove(Collections.singletonList(entity));
    }

}
