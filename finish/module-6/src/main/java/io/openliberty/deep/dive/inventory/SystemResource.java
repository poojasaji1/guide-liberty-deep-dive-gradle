// tag::copyright[]
/*******************************************************************************
 * Copyright (c) 2022 IBM Corporation and others.
 * All rights reserved. This program and the accompanying materials
 * are made available under the terms of the Eclipse Public License v1.0
 * which accompanies this distribution, and is available at
 * http://www.eclipse.org/legal/epl-v10.html
 *
 * Contributors:
 *     IBM Corporation - Initial implementation
 *******************************************************************************/
// end::copyright[]
package io.openliberty.deep.dive.inventory;

import java.net.URI;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.eclipse.microprofile.rest.client.RestClientBuilder;

import com.ibm.websphere.security.jwt.Claims;
import com.ibm.websphere.security.jwt.JwtBuilder;

import io.openliberty.deep.dive.inventory.client.SystemClient;
import io.openliberty.deep.dive.inventory.client.UnknownUriExceptionMapper;
import io.openliberty.deep.dive.inventory.model.SystemData;
import jakarta.annotation.security.RolesAllowed;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.ws.rs.Consumes;
import jakarta.ws.rs.DELETE;
import jakarta.ws.rs.FormParam;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.PUT;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.PathParam;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.core.Context;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;

@ApplicationScoped
@Path("/systems")
public class SystemResource {

    @Inject
    Inventory inventory;

    @Inject
    @ConfigProperty(name = "client.https.port")
    String CLIENT_PORT;
    
    @GET
    @Path("/")
    @Produces(MediaType.APPLICATION_JSON)
    public List<SystemData> listContents() {
        return inventory.getSystems();
    }

    @GET
    @Path("/{hostname}")
    @Produces(MediaType.APPLICATION_JSON)
    public SystemData getSystem(@PathParam("hostname") String hostname) {
    	return inventory.getSystem(hostname);
    }

    @POST
    @Consumes(MediaType.APPLICATION_FORM_URLENCODED)
    @Produces(MediaType.APPLICATION_JSON)
    public Response addSystem(@FormParam("hostname") String hostname,
        @FormParam("osName") String osName,
        @FormParam("javaVersion") String javaVersion,
        @FormParam("heapSize") Long heapSize) {

        if (inventory.contains(hostname)) {
            return fail(hostname + " already exists.");
        }
        inventory.add(hostname, osName, javaVersion, heapSize);
        return success(hostname + " was added.");
    }

    @PUT
    @Path("/{hostname}")
    @Consumes(MediaType.APPLICATION_FORM_URLENCODED)
    @Produces(MediaType.APPLICATION_JSON)
    @RolesAllowed({ "admin", "user" })
    public Response updateSystem(@PathParam("hostname") String hostname,
        @FormParam("osName") String osName,
        @FormParam("javaVersion") String javaVersion,
        @FormParam("heapSize") Long heapSize) {

        if (!inventory.contains(hostname)) {
            return fail(hostname + " does not exists.");
        }
        inventory.update(hostname, osName, javaVersion, heapSize);
        return success(hostname + " was updated.");
    }

    @DELETE
    @Path("/{hostname}")
    @Produces(MediaType.APPLICATION_JSON)
    @RolesAllowed({ "admin" })
    public Response removeSystem(@PathParam("hostname") String hostname) {
        if (inventory.removeSystem(hostname)) {
            return success(hostname + " was removed.");
        } else {
            return fail(hostname + " does not exists.");
        }
    }

    @POST
    @Path("/client/{hostname}")
    @Consumes(MediaType.APPLICATION_FORM_URLENCODED)
    @Produces(MediaType.APPLICATION_JSON)
    @RolesAllowed({ "admin" })
    public Response addSystemClient(@PathParam("hostname") String hostname, @Context HttpServletRequest request) {
    	
        if (inventory.contains(hostname)) {
            return fail(hostname + " already exists.");
        }

        SystemClient customRestClient = null;
        try {
            customRestClient = getSystemClient(hostname);
        } catch (Exception e) {
            return fail("Failed to create the client " + hostname + ".");
        }

        String authHeader = null;
        try {
            authHeader = getAuthHeader(request);
        } catch (Exception e) {
            return fail("Failed to generate a JWT.");
        }

        try {
            String osName = customRestClient.getProperty(authHeader, "os.name");
            String javaVersion = customRestClient.getProperty(authHeader, "java.version");
            Long heapSize = customRestClient.getHeapSize(authHeader);
            inventory.add(hostname, osName, javaVersion, heapSize);
        } catch (Exception e) {
            return fail("Failed to reach the client " + hostname + ".");
        }
        return success(hostname + " was added.");
    }

    private SystemClient getSystemClient(String hostname) throws Exception {
        String customURIString = "https://" + hostname + ":" + CLIENT_PORT + "/system";
        URI customURI = URI.create(customURIString);
        return RestClientBuilder.newBuilder()
                                .baseUri(customURI)
                                .register(UnknownUriExceptionMapper.class)
                                .build(SystemClient.class);
    }

    private String getAuthHeader(HttpServletRequest request) throws Exception {
        String jwtTokenString = (String) request.getSession().getAttribute("jwt");
        if (jwtTokenString == null) {
            String userName = request.getRemoteUser();;
            Set<String> roles = new HashSet<String>();
            if (request.isUserInRole("admin")) {
                roles.add("admin");
            }
            if (request.isUserInRole("user")) {
                roles.add("user");
            };
            jwtTokenString = JwtBuilder.create("jwtInventoryBuilder")
                             .claim(Claims.SUBJECT, userName  )
                             .claim("upn", userName)
                             .claim("groups", roles.toArray(new String[roles.size()]))
                             .claim("aud", "systemService")
                             .buildJwt()
                             .compact();
        }
        String authHeader = "Bearer " + jwtTokenString;
        return authHeader;
    }

    private Response success(String message) {
        return Response.ok("{ \"ok\" : \"" + message + "\" }").build();
    }

    private Response fail(String message) {
        return Response.status(Response.Status.BAD_REQUEST)
                       .entity("{ \"error\" : \"" + message + "\" }")
                       .build();
    }
}
