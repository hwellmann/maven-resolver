package org.eclipse.aether.transport.http;

/*
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 * 
 *  http://www.apache.org/licenses/LICENSE-2.0
 * 
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the License is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 * KIND, either express or implied.  See the License for the
 * specific language governing permissions and limitations
 * under the License.
 */

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.UnsupportedEncodingException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Enumeration;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;

import org.eclipse.aether.util.ChecksumUtils;
import org.eclipse.jetty.http.HttpHeaders;
import org.eclipse.jetty.http.HttpMethods;
import org.eclipse.jetty.server.Connector;
import org.eclipse.jetty.server.Request;
import org.eclipse.jetty.server.Server;
import org.eclipse.jetty.server.handler.AbstractHandler;
import org.eclipse.jetty.server.handler.HandlerList;
import org.eclipse.jetty.server.nio.SelectChannelConnector;
import org.eclipse.jetty.server.ssl.SslSelectChannelConnector;
import org.eclipse.jetty.util.B64Code;
import org.eclipse.jetty.util.IO;
import org.eclipse.jetty.util.URIUtil;
import org.eclipse.jetty.util.ssl.SslContextFactory;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class HttpServer
{

    public static class LogEntry
    {

        public final String method;

        public final String path;

        public final Map<String, String> headers;

        public LogEntry( String method, String path, Map<String, String> headers )
        {
            this.method = method;
            this.path = path;
            this.headers = headers;
        }

        @Override
        public String toString()
        {
            return method + " " + path;
        }

    }

    public enum WebDav
    {
        /** DAV header advertised, MKCOL required for missing parent directories */
        REQUIRED,
        /** DAV header advertised, MKCOL supported but not required */
        OPTIONAL
    }

    public enum ExpectContinue
    {
        /** reject request with "Expectation Failed" */
        FAIL,
        /** send "Continue" only if request made it past authentication */
        PROPER,
        /** send "Continue" before authentication has been checked */
        BROKEN
    }

    public enum ChecksumHeader
    {
        NEXUS
    }

    private static final Logger log = LoggerFactory.getLogger( HttpServer.class );

    private String serverHeader = "Dummy";

    private File repoDir;

    private boolean rangeSupport = true;

    private WebDav webDav;

    private ExpectContinue expectContinue = ExpectContinue.PROPER;

    private ChecksumHeader checksumHeader;

    private Server server;

    private Connector httpConnector;

    private Connector httpsConnector;

    private String credentialEncoding = StandardCharsets.ISO_8859_1.name();

    private String username;

    private String password;

    private String proxyUsername;

    private String proxyPassword;

    private List<LogEntry> logEntries = Collections.synchronizedList( new ArrayList<LogEntry>() );

    public String getHost()
    {
        return "localhost";
    }

    public int getHttpPort()
    {
        return httpConnector != null ? httpConnector.getLocalPort() : -1;
    }

    public int getHttpsPort()
    {
        return httpsConnector != null ? httpsConnector.getLocalPort() : -1;
    }

    public String getHttpUrl()
    {
        return "http://" + getHost() + ":" + getHttpPort();
    }

    public String getHttpsUrl()
    {
        return "https://" + getHost() + ":" + getHttpsPort();
    }

    public HttpServer addSslConnector()
    {
        if ( httpsConnector == null )
        {
            SslContextFactory ssl = new SslContextFactory();
            ssl.setKeyStorePath( new File( "src/test/resources/ssl/server-store" ).getAbsolutePath() );
            ssl.setKeyStorePassword( "server-pwd" );
            ssl.setTrustStore( new File( "src/test/resources/ssl/client-store" ).getAbsolutePath() );
            ssl.setTrustStorePassword( "client-pwd" );
            ssl.setNeedClientAuth( true );
            httpsConnector = new SslSelectChannelConnector( ssl );
            if ( server != null )
            {
                server.addConnector( httpsConnector );
                try
                {
                    httpsConnector.start();
                }
                catch ( Exception e )
                {
                    throw new IllegalStateException( e );
                }
            }
        }
        return this;
    }

    public List<LogEntry> getLogEntries()
    {
        return logEntries;
    }

    public HttpServer setServer( String server )
    {
        this.serverHeader = server;
        return this;
    }

    public HttpServer setRepoDir( File repoDir )
    {
        this.repoDir = repoDir;
        return this;
    }

    public HttpServer setRangeSupport( boolean rangeSupport )
    {
        this.rangeSupport = rangeSupport;
        return this;
    }

    public HttpServer setWebDav( WebDav webDav )
    {
        this.webDav = webDav;
        return this;
    }

    public HttpServer setExpectSupport( ExpectContinue expectContinue )
    {
        this.expectContinue = expectContinue;
        return this;
    }

    public HttpServer setChecksumHeader( ChecksumHeader checksumHeader )
    {
        this.checksumHeader = checksumHeader;
        return this;
    }

    public HttpServer setCredentialEncoding( String credentialEncoding )
    {
        this.credentialEncoding = ( credentialEncoding != null ) ? credentialEncoding : StandardCharsets.ISO_8859_1.name();
        return this;
    }

    public HttpServer setAuthentication( String username, String password )
    {
        this.username = username;
        this.password = password;
        return this;
    }

    public HttpServer setProxyAuthentication( String username, String password )
    {
        proxyUsername = username;
        proxyPassword = password;
        return this;
    }

    public HttpServer start()
        throws Exception
    {
        if ( server != null )
        {
            return this;
        }

        httpConnector = new SelectChannelConnector();

        HandlerList handlers = new HandlerList();
        handlers.addHandler( new CommonHandler() );
        handlers.addHandler( new LogHandler() );
        handlers.addHandler( new ProxyAuthHandler() );
        handlers.addHandler( new AuthHandler() );
        handlers.addHandler( new RedirectHandler() );
        handlers.addHandler( new RepoHandler() );

        server = new Server();
        server.addConnector( httpConnector );
        if ( httpsConnector != null )
        {
            server.addConnector( httpsConnector );
        }
        server.setHandler( handlers );
        server.start();

        return this;
    }

    public void stop()
        throws Exception
    {
        if ( server != null )
        {
            server.stop();
            server = null;
            httpConnector = null;
            httpsConnector = null;
        }
    }

    private class CommonHandler
        extends AbstractHandler
    {

        public void handle( String target, Request req, HttpServletRequest request, HttpServletResponse response )
            throws IOException
        {
            response.setHeader( HttpHeaders.SERVER, serverHeader );
        }

    }

    private class LogHandler
        extends AbstractHandler
    {

        @SuppressWarnings( "unchecked" )
        public void handle( String target, Request req, HttpServletRequest request, HttpServletResponse response )
            throws IOException
        {
            log.info( "{} {}{}", new Object[] { req.getMethod(), req.getRequestURL(),
                req.getQueryString() != null ? "?" + req.getQueryString() : "" } );

            Map<String, String> headers = new TreeMap<String, String>( String.CASE_INSENSITIVE_ORDER );
            for ( Enumeration<String> en = req.getHeaderNames(); en.hasMoreElements(); )
            {
                String name = en.nextElement();
                StringBuilder buffer = new StringBuilder( 128 );
                for ( Enumeration<String> ien = req.getHeaders( name ); ien.hasMoreElements(); )
                {
                    if ( buffer.length() > 0 )
                    {
                        buffer.append( ", " );
                    }
                    buffer.append( ien.nextElement() );
                }
                headers.put( name, buffer.toString() );
            }
            logEntries.add( new LogEntry( req.getMethod(), req.getPathInfo(), Collections.unmodifiableMap( headers ) ) );
        }

    }

    private class RepoHandler
        extends AbstractHandler
    {

        private final Pattern SIMPLE_RANGE = Pattern.compile( "bytes=([0-9])+-" );

        public void handle( String target, Request req, HttpServletRequest request, HttpServletResponse response )
            throws IOException
        {
            String path = req.getPathInfo().substring( 1 );

            if ( !path.startsWith( "repo/" ) )
            {
                return;
            }
            req.setHandled( true );

            if ( ExpectContinue.FAIL.equals( expectContinue ) && request.getHeader( HttpHeaders.EXPECT ) != null )
            {
                response.setStatus( HttpServletResponse.SC_EXPECTATION_FAILED );
                return;
            }

            File file = new File( repoDir, path.substring( 5 ) );
            if ( HttpMethods.GET.equals( req.getMethod() ) || HttpMethods.HEAD.equals( req.getMethod() ) )
            {
                if ( !file.isFile() || path.endsWith( URIUtil.SLASH ) )
                {
                    response.setStatus( HttpServletResponse.SC_NOT_FOUND );
                    return;
                }
                long ifUnmodifiedSince = request.getDateHeader( HttpHeaders.IF_UNMODIFIED_SINCE );
                if ( ifUnmodifiedSince != -1 && file.lastModified() > ifUnmodifiedSince )
                {
                    response.setStatus( HttpServletResponse.SC_PRECONDITION_FAILED );
                    return;
                }
                long offset = 0L;
                String range = request.getHeader( HttpHeaders.RANGE );
                if ( range != null && rangeSupport )
                {
                    Matcher m = SIMPLE_RANGE.matcher( range );
                    if ( m.matches() )
                    {
                        offset = Long.parseLong( m.group( 1 ) );
                        if ( offset >= file.length() )
                        {
                            response.setStatus( HttpServletResponse.SC_REQUESTED_RANGE_NOT_SATISFIABLE );
                            return;
                        }
                    }
                    String encoding = request.getHeader( HttpHeaders.ACCEPT_ENCODING );
                    if ( ( encoding != null && !"identity".equals( encoding ) ) || ifUnmodifiedSince == -1 )
                    {
                        response.setStatus( HttpServletResponse.SC_BAD_REQUEST );
                        return;
                    }
                }
                response.setStatus( ( offset > 0L ) ? HttpServletResponse.SC_PARTIAL_CONTENT : HttpServletResponse.SC_OK );
                response.setDateHeader( HttpHeaders.LAST_MODIFIED, file.lastModified() );
                response.setHeader( HttpHeaders.CONTENT_LENGTH, Long.toString( file.length() - offset ) );
                if ( offset > 0L )
                {
                    response.setHeader( HttpHeaders.CONTENT_RANGE, "bytes " + offset + "-" + ( file.length() - 1L )
                        + "/" + file.length() );
                }
                if ( checksumHeader != null )
                {
                    Map<String, Object> checksums = ChecksumUtils.calc( file, Collections.singleton( "SHA-1" ) );
                    switch ( checksumHeader )
                    {
                        case NEXUS:
                            response.setHeader( HttpHeaders.ETAG, "{SHA1{" + checksums.get( "SHA-1" ) + "}}" );
                            break;
                    }
                }
                if ( HttpMethods.HEAD.equals( req.getMethod() ) )
                {
                    return;
                }
                FileInputStream is = new FileInputStream( file );
                try
                {
                    if ( offset > 0L )
                    {
                        long skipped = is.skip( offset );
                        while ( skipped < offset && is.read() >= 0 )
                        {
                            skipped++;
                        }
                    }
                    IO.copy( is, response.getOutputStream() );
                }
                finally
                {
                    IO.close( is );
                }
            }
            else if ( HttpMethods.PUT.equals( req.getMethod() ) )
            {
                if ( !WebDav.REQUIRED.equals( webDav ) )
                {
                    file.getParentFile().mkdirs();
                }
                if ( file.getParentFile().exists() )
                {
                    try
                    {
                        FileOutputStream os = new FileOutputStream( file );
                        try
                        {
                            IO.copy( request.getInputStream(), os );
                        }
                        finally
                        {
                            os.close();
                        }
                    }
                    catch ( IOException e )
                    {
                        file.delete();
                        throw e;
                    }
                    response.setStatus( HttpServletResponse.SC_NO_CONTENT );
                }
                else
                {
                    response.setStatus( HttpServletResponse.SC_FORBIDDEN );
                }
            }
            else if ( HttpMethods.OPTIONS.equals( req.getMethod() ) )
            {
                if ( webDav != null )
                {
                    response.setHeader( "DAV", "1,2" );
                }
                response.setHeader( HttpHeaders.ALLOW, "GET, PUT, HEAD, OPTIONS" );
                response.setStatus( HttpServletResponse.SC_OK );
            }
            else if ( webDav != null && "MKCOL".equals( req.getMethod() ) )
            {
                if ( file.exists() )
                {
                    response.setStatus( HttpServletResponse.SC_METHOD_NOT_ALLOWED );
                }
                else if ( file.mkdir() )
                {
                    response.setStatus( HttpServletResponse.SC_CREATED );
                }
                else
                {
                    response.setStatus( HttpServletResponse.SC_CONFLICT );
                }
            }
            else
            {
                response.setStatus( HttpServletResponse.SC_METHOD_NOT_ALLOWED );
            }
        }

    }

    private class RedirectHandler
        extends AbstractHandler
    {

        public void handle( String target, Request req, HttpServletRequest request, HttpServletResponse response )
            throws IOException
        {
            String path = req.getPathInfo();
            if ( !path.startsWith( "/redirect/" ) )
            {
                return;
            }
            req.setHandled( true );
            StringBuilder location = new StringBuilder( 128 );
            String scheme = req.getParameter( "scheme" );
            String host = req.getParameter( "host" );
            String port = req.getParameter( "port" );
            location.append( scheme != null ? scheme : req.getScheme() );
            location.append( "://" );
            location.append( host != null ? host : req.getServerName() );
            location.append( ":" );
            if ( port != null )
            {
                location.append( port );
            }
            else if ( "http".equalsIgnoreCase( scheme ) )
            {
                location.append( getHttpPort() );
            }
            else if ( "https".equalsIgnoreCase( scheme ) )
            {
                location.append( getHttpsPort() );
            }
            else
            {
                location.append( req.getServerPort() );
            }
            location.append( "/repo" ).append( path.substring( 9 ) );
            response.setStatus( HttpServletResponse.SC_MOVED_PERMANENTLY );
            response.setHeader( HttpHeaders.LOCATION, location.toString() );
        }

    }

    private class AuthHandler
        extends AbstractHandler
    {

        public void handle( String target, Request req, HttpServletRequest request, HttpServletResponse response )
            throws IOException
        {
            if ( ExpectContinue.BROKEN.equals( expectContinue )
                && "100-continue".equalsIgnoreCase( request.getHeader( HttpHeaders.EXPECT ) ) )
            {
                request.getInputStream();
            }

            if ( username != null && password != null )
            {
                if ( checkBasicAuth( request.getHeader( HttpHeaders.AUTHORIZATION ), username, password ) )
                {
                    return;
                }
                req.setHandled( true );
                response.setHeader( HttpHeaders.WWW_AUTHENTICATE, "basic realm=\"Test-Realm\"" );
                response.setStatus( HttpServletResponse.SC_UNAUTHORIZED );
            }
        }

    }

    private class ProxyAuthHandler
        extends AbstractHandler
    {

        public void handle( String target, Request req, HttpServletRequest request, HttpServletResponse response )
            throws IOException
        {
            if ( proxyUsername != null && proxyPassword != null )
            {
                if ( checkBasicAuth( request.getHeader( HttpHeaders.PROXY_AUTHORIZATION ), proxyUsername, proxyPassword ) )
                {
                    return;
                }
                req.setHandled( true );
                response.setHeader( HttpHeaders.PROXY_AUTHENTICATE, "basic realm=\"Test-Realm\"" );
                response.setStatus( HttpServletResponse.SC_PROXY_AUTHENTICATION_REQUIRED );
            }
        }

    }

    boolean checkBasicAuth( String credentials, String username, String password )
    {
        if ( credentials != null )
        {
            int space = credentials.indexOf( ' ' );
            if ( space > 0 )
            {
                String method = credentials.substring( 0, space );
                if ( "basic".equalsIgnoreCase( method ) )
                {
                    credentials = credentials.substring( space + 1 );
                    try
                    {
                        credentials = B64Code.decode( credentials, credentialEncoding );
                    }
                    catch ( UnsupportedEncodingException e )
                    {
                        throw new IllegalStateException( e );
                    }
                    int i = credentials.indexOf( ':' );
                    if ( i > 0 )
                    {
                        String user = credentials.substring( 0, i );
                        String pass = credentials.substring( i + 1 );
                        if ( username.equals( user ) && password.equals( pass ) )
                        {
                            return true;
                        }
                    }
                }
            }
        }
        return false;
    }

}
