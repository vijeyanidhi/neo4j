/*
 * Copyright (c) 2002-2019 "Neo4j,"
 * Neo4j Sweden AB [http://neo4j.com]
 *
 * This file is part of Neo4j Enterprise Edition. The included source
 * code can be redistributed and/or modified under the terms of the
 * GNU AFFERO GENERAL PUBLIC LICENSE Version 3
 * (http://www.fsf.org/licensing/licenses/agpl-3.0.html) with the
 * Commons Clause, as found in the associated LICENSE.txt file.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU Affero General Public License for more details.
 *
 * Neo4j object code can be licensed independently from the source
 * under separate terms from the AGPL. Inquiries can be directed to:
 * licensing@neo4j.com
 *
 * More information is also available at:
 * https://neo4j.com/licensing/
 */
package org.neo4j.annotations.service;

import org.eclipse.collections.api.multimap.MutableMultimap;
import org.eclipse.collections.impl.factory.Multimaps;

import java.io.IOException;
import java.io.PrintWriter;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import javax.annotation.processing.AbstractProcessor;
import javax.annotation.processing.ProcessingEnvironment;
import javax.annotation.processing.RoundEnvironment;
import javax.lang.model.SourceVersion;
import javax.lang.model.element.Element;
import javax.lang.model.element.TypeElement;
import javax.lang.model.type.TypeMirror;
import javax.lang.model.util.Elements;
import javax.lang.model.util.Types;
import javax.tools.FileObject;

import static java.lang.String.format;
import static java.util.Comparator.comparing;
import static java.util.stream.Collectors.toList;
import static java.util.stream.Collectors.toSet;
import static javax.tools.Diagnostic.Kind.ERROR;
import static javax.tools.Diagnostic.Kind.NOTE;
import static javax.tools.StandardLocation.CLASS_OUTPUT;
import static org.apache.commons.lang3.exception.ExceptionUtils.getStackTrace;
import static org.eclipse.collections.impl.set.mutable.UnifiedSet.newSetWith;

public class ServiceAnnotationProcessor extends AbstractProcessor
{
    private final MutableMultimap<TypeElement, TypeElement> serviceProviders = Multimaps.mutable.list.empty();
    private Types typeUtils;
    private Elements elementUtils;

    @Override
    public synchronized void init( ProcessingEnvironment processingEnv )
    {
        super.init( processingEnv );
        typeUtils = processingEnv.getTypeUtils();
        elementUtils = processingEnv.getElementUtils();
    }

    @Override
    public Set<String> getSupportedAnnotationTypes()
    {
        return newSetWith( ServiceProvider.class.getName() );
    }

    @Override
    public SourceVersion getSupportedSourceVersion()
    {
        return SourceVersion.latestSupported();
    }

    @Override
    public boolean process( Set<? extends TypeElement> annotations, RoundEnvironment roundEnv )
    {
        try
        {
            if ( roundEnv.processingOver() )
            {
                if ( !roundEnv.errorRaised() )
                {
                    generateConfigs();
                }
            }
            else
            {
                scan( roundEnv );
            }
        }
        catch ( Exception e )
        {
            error( "Service annotation processor failed", e );
        }
        return false;
    }

    private void scan( RoundEnvironment roundEnv )
    {
        final Set<TypeElement> elements = roundEnv.getElementsAnnotatedWith( ServiceProvider.class ).stream().map( TypeElement.class::cast ).collect( toSet() );
        info( "Processing service providers: " + elements );
        for ( TypeElement serviceProvider : elements )
        {
            getImplementedService( serviceProvider ).ifPresent( service ->
            {
                info( format( "Service %s provided by %s", service, serviceProvider ) );
                serviceProviders.put( service, serviceProvider );
            } );
        }
    }

    private Optional<TypeElement> getImplementedService( TypeElement serviceProvider )
    {
        final Set<TypeMirror> supertypes = getAllSupertypes( serviceProvider.asType() );
        supertypes.add( serviceProvider.asType() );
        final List<TypeMirror> services = supertypes.stream().filter( this::isService ).collect( toList() );

        if ( services.isEmpty() )
        {
            error( format( "Service provider %s neither has ascendants nor itself annotated with @Service)", serviceProvider ), serviceProvider );
            return Optional.empty();
        }

        if ( services.size() > 1 )
        {
            error( format( "Service provider %s has multiple ascendants annotated with @Service: %s", serviceProvider, services ), serviceProvider );
            return Optional.empty();
        }

        return Optional.of( (TypeElement) typeUtils.asElement( services.get( 0 ) ) );
    }

    private boolean isService( TypeMirror type )
    {
        return typeUtils.asElement( type ).getAnnotation( Service.class ) != null;
    }

    private Set<TypeMirror> getAllSupertypes( TypeMirror type )
    {
        final List<? extends TypeMirror> directSupertypes = typeUtils.directSupertypes( type );
        final Set<TypeMirror> allSupertypes = new HashSet<>( directSupertypes );
        directSupertypes.forEach( directSupertype -> allSupertypes.addAll( getAllSupertypes( directSupertype ) ) );
        return allSupertypes;
    }

    private void generateConfigs() throws IOException
    {
        for ( final TypeElement service : serviceProviders.keySet() )
        {
            final String path = "META-INF/services/" + elementUtils.getBinaryName( service ).toString();
            info( "Generating service config file: " + path );
            final FileObject file = processingEnv.getFiler().createResource( CLASS_OUTPUT, "", path );
            try ( PrintWriter out = new PrintWriter( file.openWriter() ) )
            {
                serviceProviders.get( service ).stream()
                        .sorted( comparing( typeElement -> typeElement.getQualifiedName().toString() ) )
                        .forEach( provider ->
                        {
                            final String providerName = elementUtils.getBinaryName( provider ).toString();
                            info( "Writing provider:  " + providerName );
                            out.println( providerName );
                        } );
            }
        }
    }

    private void info( String msg )
    {
        processingEnv.getMessager().printMessage( NOTE, msg );
    }

    private void error( String msg, Exception e )
    {
        processingEnv.getMessager().printMessage( ERROR, msg + ": " + getStackTrace( e ) );
    }

    private void error( String msg, Element element )
    {
        processingEnv.getMessager().printMessage( ERROR, msg, element );
    }
}
