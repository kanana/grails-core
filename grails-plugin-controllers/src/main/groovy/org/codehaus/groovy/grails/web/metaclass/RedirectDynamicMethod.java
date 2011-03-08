/*
 * Copyright 2004-2005 the original author or authors.
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
package org.codehaus.groovy.grails.web.metaclass;

import grails.util.GrailsNameUtils;
import groovy.lang.Closure;
import groovy.lang.GroovyObject;
import groovy.lang.MissingMethodException;

import java.io.IOException;
import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.Map;
import java.util.regex.Pattern;

import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;

import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;
import org.codehaus.groovy.grails.commons.ControllerArtefactHandler;
import org.codehaus.groovy.grails.commons.GrailsApplication;
import org.codehaus.groovy.grails.commons.GrailsClassUtils;
import org.codehaus.groovy.grails.commons.metaclass.AbstractDynamicMethodInvocation;
import org.codehaus.groovy.grails.web.mapping.UrlCreator;
import org.codehaus.groovy.grails.web.mapping.UrlMappingsHolder;
import org.codehaus.groovy.grails.web.servlet.GrailsApplicationAttributes;
import org.codehaus.groovy.grails.web.servlet.mvc.GrailsWebRequest;
import org.codehaus.groovy.grails.web.servlet.mvc.RedirectEventListener;
import org.codehaus.groovy.grails.web.servlet.mvc.exceptions.CannotRedirectException;
import org.springframework.context.ApplicationContext;
import org.springframework.validation.Errors;
import org.springframework.web.context.request.RequestContextHolder;

/**
 * Implements the "redirect" Controller method for action redirection.
 *
 * @author Graeme Rocher
 * @since 0.2
 *
 * Created Oct 27, 2005
 */
public class RedirectDynamicMethod extends AbstractDynamicMethodInvocation {

    public static final String METHOD_SIGNATURE = "redirect";
    public static final Pattern METHOD_PATTERN = Pattern.compile('^'+METHOD_SIGNATURE+'$');
    public static final String ARGUMENT_URI = "uri";
    public static final String ARGUMENT_URL = "url";
    public static final String ARGUMENT_CONTROLLER = "controller";
    public static final String ARGUMENT_ACTION = "action";
    public static final String ARGUMENT_ID = "id";
    public static final String ARGUMENT_PARAMS = "params";
    public static final String GRAILS_VIEWS_ENABLE_JSESSIONID = "grails.views.enable.jsessionid";
    public static final String GRAILS_REDIRECT_ISSUED = "org.codehaus.groovy.grails.REDIRECT_ISSUED";

    private static final String ARGUMENT_FRAGMENT = "fragment";
    public static final String ARGUMENT_ERRORS = "errors";

    private static final Log LOG = LogFactory.getLog(RedirectDynamicMethod.class);
    private boolean useJessionId = false;
    private Collection<RedirectEventListener> redirectListeners;

    /**
     */
    public RedirectDynamicMethod(Collection<RedirectEventListener> redirectListeners) {
        super(METHOD_PATTERN);

        this.redirectListeners = redirectListeners;
    }

    public RedirectDynamicMethod() {
        super(METHOD_PATTERN);
    }

    public void setRedirectListeners(Collection<RedirectEventListener> redirectListeners) {
        this.redirectListeners = redirectListeners;
    }

    public void setUseJessionId(boolean useJessionId) {
        this.useJessionId = useJessionId;
    }

    @SuppressWarnings({"unchecked","rawtypes"})
    @Override
    public Object invoke(Object target, String methodName, Object[] arguments) {
        if (arguments.length == 0) {
            throw new MissingMethodException(METHOD_SIGNATURE,target.getClass(),arguments);
        }

        Map argMap = arguments[0] instanceof Map ? (Map)arguments[0] : Collections.EMPTY_MAP;
        if (argMap.isEmpty()) {
            throw new MissingMethodException(METHOD_SIGNATURE,target.getClass(),arguments);
        }

        GrailsWebRequest webRequest = (GrailsWebRequest)RequestContextHolder.currentRequestAttributes();
        UrlMappingsHolder urlMappingsHolder = webRequest.getApplicationContext().getBean(UrlMappingsHolder.BEAN_ID, UrlMappingsHolder.class);

        HttpServletRequest request = webRequest.getCurrentRequest();
        if (request.getAttribute(GRAILS_REDIRECT_ISSUED) != null) {
            throw new CannotRedirectException("Cannot issue a redirect(..) here. A previous call to redirect(..) has already redirected the response.");
        }

        HttpServletResponse response = webRequest.getCurrentResponse();
        if (response.isCommitted()) {
            throw new CannotRedirectException("Cannot issue a redirect(..) here. The response has already been committed either by another redirect or by directly writing to the response.");
        }

        GroovyObject controller = (GroovyObject)target;

        // if there are errors add it to the list of errors
        Errors controllerErrors = (Errors)controller.getProperty(ControllerDynamicMethods.ERRORS_PROPERTY);
        Errors errors = (Errors)argMap.get(ARGUMENT_ERRORS);
        if (controllerErrors != null) {
            controllerErrors.addAllErrors(errors);
        }
        else {
            controller.setProperty(ControllerDynamicMethods.ERRORS_PROPERTY, errors);
        }

        Object uri = argMap.get(ARGUMENT_URI);
        String url = argMap.containsKey(ARGUMENT_URL) ? argMap.get(ARGUMENT_URL).toString() : null;
        String actualUri;
        if (uri != null) {
            GrailsApplicationAttributes attrs = webRequest.getAttributes();
            actualUri = attrs.getApplicationUri(request) + uri.toString();
        }
        else if (url != null) {
            actualUri = url;
        }
        else {
            Object actionRef = argMap.get(ARGUMENT_ACTION);
            String actionName = establishActionName(actionRef, target);
            String controllerName = getControllerName(target, argMap);
            controllerName = controllerName != null ? controllerName : webRequest.getControllerName();

            Map params = (Map)argMap.get(ARGUMENT_PARAMS);
            if (params == null) {
                params = new HashMap();
            }

            if (LOG.isDebugEnabled()) {
                LOG.debug("Dynamic method [redirect] looking up URL mapping for controller ["+controllerName+"] and action ["+actionName+"] and params ["+params+"] with ["+urlMappingsHolder+"]");
            }

            Object id = argMap.get(ARGUMENT_ID);
            try {
                if (id != null) {
                    params.put(ARGUMENT_ID, id);
                }

                UrlCreator urlMapping = urlMappingsHolder.getReverseMapping(controllerName, actionName, params);
                if (urlMapping == null && LOG.isDebugEnabled()) {
                    LOG.debug("Dynamic method [redirect] no URL mapping found for params [" + params + "]");
                }

                String frag = argMap.get(ARGUMENT_FRAGMENT) != null ? argMap.get(ARGUMENT_FRAGMENT).toString() : null;
                actualUri = urlMapping.createURL(controllerName, actionName, params, request.getCharacterEncoding(), frag);

                if (LOG.isDebugEnabled()) {
                    LOG.debug("Dynamic method [redirect] mapped to URL [" + actualUri + "]");
                }
            }
            finally {
                if (id != null) {
                    params.remove(ARGUMENT_ID);
                }
            }
        }

        return redirectResponse(actualUri, request,response);
    }

    @SuppressWarnings("rawtypes")
    private String getControllerName(Object target, Map argMap) {
        return argMap.containsKey(ARGUMENT_CONTROLLER) ?
                argMap.get(ARGUMENT_CONTROLLER).toString() :
                GrailsNameUtils.getLogicalPropertyName(target.getClass().getName(),
                        ControllerArtefactHandler.TYPE);
    }

    /*
     * Redirects the response the the given URI
     */
    private Object redirectResponse(String actualUri, HttpServletRequest request, HttpServletResponse response) {
        if (LOG.isDebugEnabled()) {
            LOG.debug("Dynamic method [redirect] forwarding request to ["+actualUri +"]");
        }

        try {
            if (LOG.isDebugEnabled()) {
                LOG.debug("Executing redirect with response ["+response+"]");
            }

            String redirectUrl = useJessionId ? response.encodeRedirectURL(actualUri) : actualUri;
            response.sendRedirect(redirectUrl);
            if(redirectListeners != null) {
                for (RedirectEventListener redirectEventListener : redirectListeners) {
                    redirectEventListener.responseRedirected(redirectUrl);
                }
            }

            request.setAttribute(GRAILS_REDIRECT_ISSUED, true);
        }
        catch (IOException e) {
            throw new CannotRedirectException("Error redirecting request for url ["+actualUri +"]: " + e.getMessage(),e);
        }
        return null;
    }

    /*
     * Figures out the action name from the specified action reference (either a string or closure)
     */
    private String establishActionName(Object actionRef, Object target) {
        String actionName = null;
        if (actionRef instanceof String) {
            actionName = (String)actionRef;
        }
        else if (actionRef instanceof CharSequence) {
            actionName = actionRef.toString();
        }
        else if (actionRef instanceof Closure) {
            actionName = GrailsClassUtils.findPropertyNameForValue(target, actionRef);
        }
        return actionName;
    }
}
