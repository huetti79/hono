/**
 * Copyright (c) 2016 Bosch Software Innovations GmbH.
 *
 * All rights reserved. This program and the accompanying materials
 * are made available under the terms of the Eclipse Public License v1.0
 * which accompanies this distribution, and is available at
 * http://www.eclipse.org/legal/epl-v10.html
 *
 * Contributors:
 *    Bosch Software Innovations GmbH - initial creation
 */
package org.eclipse.hono.authorization.impl;

import static java.nio.charset.StandardCharsets.UTF_8;
import static java.util.Objects.requireNonNull;
import static java.util.Optional.ofNullable;

import java.io.IOException;
import java.net.URI;
import java.net.URL;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.EnumSet;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.stream.Collectors;

import org.eclipse.hono.authorization.AccessControlList;
import org.eclipse.hono.authorization.AclEntry;
import org.eclipse.hono.authorization.Permission;
import org.eclipse.hono.util.ResourceIdentifier;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import io.vertx.core.Future;
import io.vertx.core.json.JsonArray;
import io.vertx.core.json.JsonObject;

/**
 * Implementation of AuthorizationService that holds acl data in memory i.e. no persistent storage.
 */
public final class InMemoryAuthorizationService extends BaseAuthorizationService {

    static final String PERMISSIONS_FILE_PATH = "/config/permissions.json";
    private static final Logger LOGGER = LoggerFactory.getLogger(InMemoryAuthorizationService.class);
    // holds mapping resource -> acl
    private static final ConcurrentMap<ResourceIdentifier, AccessControlList> resources = new ConcurrentHashMap<>();
    private final String permissionsPath;

    public InMemoryAuthorizationService() {
        this(false, PERMISSIONS_FILE_PATH);
    }

    /**
     * 
     */
    public InMemoryAuthorizationService(final boolean singleTenant, final String permissionsPath) {
        this(0, 1, singleTenant, permissionsPath);
    }

    /**
     * 
     */
    public InMemoryAuthorizationService(final int instanceId, final int totalNoOfInstances, final boolean singleTenant, final String permissionsPath) {
        super(instanceId, totalNoOfInstances, singleTenant);
        this.permissionsPath = permissionsPath;
    }

    @Override
    protected void doStart(final Future<Void> startFuture) throws Exception {
        loadPermissionsFromFile();
        startFuture.complete();
    }

    @Override
    public boolean hasPermission(final String subject, final ResourceIdentifier resource, final Permission permission) {
        requireNonNull(subject, "subject is required");
        requireNonNull(resource, "resources is required");
        requireNonNull(permission, "permission is required");

        return hasPermissionForTenant(subject, resource, permission) || hasPermissionInternal(subject, resource, permission);
    }

    private boolean hasPermissionForTenant(final String subject, final ResourceIdentifier resource, final Permission permission) {
        if (resource.getDeviceId() != null) {
            final ResourceIdentifier tenantResource = ResourceIdentifier.from(resource.getEndpoint(), resource.getTenantId(), null);
            return hasPermissionInternal(subject, tenantResource, permission);
        }
        return false;
    }

    private boolean hasPermissionInternal(final String subject, final ResourceIdentifier resource, final Permission permission) {
        return ofNullable(resources.get(resource)).map(acl -> acl.hasPermission(subject, permission)).orElse(false);
    }

    @Override
    public void addPermission(final String subject, final ResourceIdentifier resource, final Permission first,
            final Permission... rest) {
        requireNonNull(first, "permission is required");
        final EnumSet<Permission> permissions = EnumSet.of(first, rest);
        addPermission(subject, resource, permissions);
    }

    @Override
    public void addPermission(final String subject, final ResourceIdentifier resource, final Set<Permission> permissions) {
        requireNonNull(subject, "subject is required");
        requireNonNull(resource, "resource is required");
        requireNonNull(permissions, "permission is required");

        LOGGER.debug("Add permission {} for subject {} on resource {}.", permissions, subject, resource);
        resources.computeIfAbsent(resource, key -> new AccessControlList())
                .setAclEntry(new AclEntry(subject, permissions));
    }

    @Override
    public void removePermission(final String subject, final ResourceIdentifier resource, final Permission first,
            final Permission... rest) {
        requireNonNull(subject, "subject is required");
        requireNonNull(resource, "resource is required");
        requireNonNull(first, "permission is required");

        final EnumSet<Permission> permissions = EnumSet.of(first, rest);
        LOGGER.debug("Delete permission {} for subject {} on resource {}.", first, subject, resource);
        resources.computeIfPresent(resource, (key, value) -> {
            ofNullable(value.getAclEntry(subject))
                    .map(AclEntry::getPermissions)
                    .ifPresent(p -> p.removeAll(permissions));
            return value;
        });
    }

    private void loadPermissionsFromFile() throws IOException {

            final Path pathToPermissions = resolvePathToPermissions();
            LOGGER.debug("Loading permissions from: {}", pathToPermissions.toAbsolutePath());
            final String permissionsJson = new String(Files.readAllBytes(pathToPermissions), UTF_8);
            final JsonObject permissionsObject = new JsonObject(permissionsJson);

            permissionsObject
                    .stream().filter(resources -> resources.getValue() instanceof JsonObject)
                    .forEach(resources -> {
                        final ResourceIdentifier resourceIdentifier = getResourceIdentifier(resources);
                        final JsonObject subjects = (JsonObject) resources.getValue();
                        subjects
                                .stream().filter(subject -> subject.getValue() instanceof JsonArray)
                                .forEach(subject -> {
                                    final JsonArray permissions = (JsonArray) subject.getValue();
                                    addPermission(subject.getKey(), resourceIdentifier, toSet(permissions));
                                });
                    });
    }

    private Path resolvePathToPermissions() {
        // first try to load from configured path
        final Path pathToPermissions = Paths.get(permissionsPath);
        if (pathToPermissions != null && pathToPermissions.toFile().exists() && pathToPermissions.toFile().canRead()) {
            return pathToPermissions;
        }

        // then try to load default from classpath
        final URL resource = InMemoryAuthorizationService.class.getResource(permissionsPath);
        return Paths.get(URI.create(resource.toString()));
    }

    private ResourceIdentifier getResourceIdentifier(final Map.Entry<String, Object> resources) {
        if (singleTenant) {
            return ResourceIdentifier.fromStringAssumingDefaultTenant(resources.getKey());
        } else {
            return ResourceIdentifier.fromString(resources.getKey());
        }
    }

    private Set<Permission> toSet(final JsonArray array) {
        return array.stream()
                .filter(element -> element instanceof String)
                .map(element -> (String) element)
                .map(Permission::valueOf)
                .collect(Collectors.<Permission>toSet());
    }
}
