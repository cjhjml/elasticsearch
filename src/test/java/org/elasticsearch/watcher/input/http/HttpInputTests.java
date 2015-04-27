/*
 * Copyright Elasticsearch B.V. and/or licensed to Elasticsearch B.V. under one
 * or more contributor license agreements. Licensed under the Elastic License;
 * you may not use this file except in compliance with the Elastic License.
 */
package org.elasticsearch.watcher.input.http;

import com.carrotsearch.randomizedtesting.annotations.Repeat;
import org.elasticsearch.ElasticsearchIllegalArgumentException;
import org.elasticsearch.common.bytes.BytesReference;
import org.elasticsearch.common.collect.ImmutableMap;
import org.elasticsearch.common.collect.MapBuilder;
import org.elasticsearch.common.joda.time.DateTime;
import org.elasticsearch.common.settings.ImmutableSettings;
import org.elasticsearch.common.unit.TimeValue;
import org.elasticsearch.common.xcontent.XContentBuilder;
import org.elasticsearch.common.xcontent.XContentHelper;
import org.elasticsearch.common.xcontent.XContentParser;
import org.elasticsearch.test.ElasticsearchTestCase;
import org.elasticsearch.watcher.actions.ActionStatus;
import org.elasticsearch.watcher.actions.ActionWrapper;
import org.elasticsearch.watcher.actions.ExecutableActions;
import org.elasticsearch.watcher.condition.always.ExecutableAlwaysCondition;
import org.elasticsearch.watcher.execution.TriggeredExecutionContext;
import org.elasticsearch.watcher.execution.WatchExecutionContext;
import org.elasticsearch.watcher.input.InputBuilders;
import org.elasticsearch.watcher.input.simple.ExecutableSimpleInput;
import org.elasticsearch.watcher.input.simple.SimpleInput;
import org.elasticsearch.watcher.support.http.*;
import org.elasticsearch.watcher.support.http.auth.HttpAuth;
import org.elasticsearch.watcher.support.http.auth.HttpAuthFactory;
import org.elasticsearch.watcher.support.http.auth.HttpAuthRegistry;
import org.elasticsearch.watcher.support.http.auth.basic.BasicAuth;
import org.elasticsearch.watcher.support.http.auth.basic.BasicAuthFactory;
import org.elasticsearch.watcher.support.secret.SecretService;
import org.elasticsearch.watcher.support.template.Template;
import org.elasticsearch.watcher.support.template.TemplateEngine;
import org.elasticsearch.watcher.trigger.schedule.IntervalSchedule;
import org.elasticsearch.watcher.trigger.schedule.ScheduleTrigger;
import org.elasticsearch.watcher.trigger.schedule.ScheduleTriggerEvent;
import org.elasticsearch.watcher.watch.Payload;
import org.elasticsearch.watcher.watch.Watch;
import org.elasticsearch.watcher.watch.WatchStatus;
import org.junit.Before;
import org.junit.Test;

import java.util.ArrayList;
import java.util.Map;

import static org.elasticsearch.common.joda.time.DateTimeZone.UTC;
import static org.elasticsearch.common.xcontent.XContentFactory.jsonBuilder;
import static org.hamcrest.Matchers.*;
import static org.mockito.Matchers.any;
import static org.mockito.Matchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 */
public class HttpInputTests extends ElasticsearchTestCase {

    private HttpClient httpClient;
    private HttpInputFactory httpParser;
    private SecretService secretService;
    private TemplateEngine templateEngine;

    @Before
    public void init() throws Exception {
        httpClient = mock(HttpClient.class);
        templateEngine = mock(TemplateEngine.class);
        secretService = mock(SecretService.class);
        HttpAuthRegistry registry = new HttpAuthRegistry(ImmutableMap.<String, HttpAuthFactory>of("basic", new BasicAuthFactory(secretService)));
        httpParser = new HttpInputFactory(ImmutableSettings.EMPTY, httpClient, templateEngine, new HttpRequest.Parser(registry), new HttpRequestTemplate.Parser(registry));
    }

    @Test
    public void testExecute() throws Exception {
        String host = "_host";
        int port = 123;
        HttpRequestTemplate.Builder request = HttpRequestTemplate.builder(host, port)
                .method(HttpMethod.POST)
                .body("_body");
        HttpInput httpInput = InputBuilders.httpInput(request.build()).build();
        ExecutableHttpInput input = new ExecutableHttpInput(httpInput, logger, httpClient, templateEngine);

        HttpResponse response = new HttpResponse(123, "{\"key\" : \"value\"}".getBytes(UTF8));
        when(httpClient.execute(any(HttpRequest.class))).thenReturn(response);

        when(templateEngine.render(eq(Template.inline("_body").build()), any(Map.class))).thenReturn("_body");

        Watch watch = new Watch("test-watch",
                new ScheduleTrigger(new IntervalSchedule(new IntervalSchedule.Interval(1, IntervalSchedule.Interval.Unit.MINUTES))),
                new ExecutableSimpleInput(new SimpleInput(new Payload.Simple()), logger),
                new ExecutableAlwaysCondition(logger),
                null,
                null,
                new ExecutableActions(new ArrayList<ActionWrapper>()),
                null,
                new WatchStatus(ImmutableMap.<String, ActionStatus>of()));
        WatchExecutionContext ctx = new TriggeredExecutionContext(watch,
                new DateTime(0, UTC),
                new ScheduleTriggerEvent(watch.id(), new DateTime(0, UTC), new DateTime(0, UTC)),
                TimeValue.timeValueSeconds(5));
        HttpInput.Result result = input.execute(ctx);
        assertThat(result.type(), equalTo(HttpInput.TYPE));
        assertThat(result.payload().data(), equalTo(MapBuilder.<String, Object>newMapBuilder().put("key", "value").map()));
    }

    @Test @Repeat(iterations = 20)
    public void testParser() throws Exception {
        final HttpMethod httpMethod = rarely() ? null : randomFrom(HttpMethod.values());
        Scheme scheme = randomFrom(Scheme.HTTP, Scheme.HTTPS, null);
        String host = randomAsciiOfLength(3);
        int port = randomIntBetween(8000, 9000);
        String path = randomAsciiOfLength(3);
        Template pathTemplate = Template.inline(path).build();
        String body = randomBoolean() ? randomAsciiOfLength(3) : null;
        Map<String, Template> params = randomBoolean() ? new MapBuilder<String, Template>().put("a", Template.inline("b").build()).map() : null;
        Map<String, Template> headers = randomBoolean() ? new MapBuilder<String, Template>().put("c", Template.inline("d").build()).map() : null;
        HttpAuth auth = randomBoolean() ? new BasicAuth("username", "password".toCharArray()) : null;
        HttpRequestTemplate.Builder requestBuilder = HttpRequestTemplate.builder(host, port)
                .scheme(scheme)
                .method(httpMethod)
                .path(pathTemplate)
                .body(body != null ? Template.inline(body).build() : null)
                .auth(auth);

        if (params != null) {
            requestBuilder.putParams(params);
        }
        if (headers != null) {
            requestBuilder.putHeaders(headers);
        }

        BytesReference source = jsonBuilder().value(InputBuilders.httpInput(requestBuilder).build()).bytes();
        XContentParser parser = XContentHelper.createParser(source);
        parser.nextToken();
        HttpInput result = httpParser.parseInput("_id", parser);

        assertThat(result.type(), equalTo(HttpInput.TYPE));
        assertThat(result.getRequest().scheme(), equalTo(scheme != null ? scheme : Scheme.HTTP)); // http is the default
        assertThat(result.getRequest().method(), equalTo(httpMethod != null ? httpMethod : HttpMethod.GET)); // get is the default
        assertThat(result.getRequest().host(), equalTo(host));
        assertThat(result.getRequest().port(), equalTo(port));
        assertThat(result.getRequest().path(), is(Template.inline(path).build()));
        if (params != null) {
            assertThat(result.getRequest().params(), hasEntry(is("a"), is(Template.inline("b").build())));
        }
        if (headers != null) {
            assertThat(result.getRequest().headers(), hasEntry(is("c"), is(Template.inline("d").build())));
        }
        assertThat(result.getRequest().auth(), equalTo(auth));
        if (body != null) {
            assertThat(result.getRequest().body(), is(Template.inline(body).build()));
        } else {
            assertThat(result.getRequest().body(), nullValue());
        }
    }

    @Test(expected = ElasticsearchIllegalArgumentException.class)
    public void testParser_invalidHttpMethod() throws Exception {
        XContentBuilder builder = jsonBuilder().startObject()
                .startObject("request")
                    .field("method", "_method")
                    .field("body", "_body")
                .endObject()
                .endObject();
        XContentParser parser = XContentHelper.createParser(builder.bytes());
        parser.nextToken();
        httpParser.parseInput("_id", parser);
    }

    @Test
    public void testParseResult() throws Exception {
        HttpMethod httpMethod = HttpMethod.GET;
        String body = "_body";
        Map<String, Template> headers = new MapBuilder<String, Template>().put("a", Template.inline("b").build()).map();
        HttpRequest request = HttpRequest.builder("_host", 123)
                .method(httpMethod)
                .body(body)
                .setHeader("a", "b")
                .build();

        Map<String, Object> payload = MapBuilder.<String, Object>newMapBuilder().put("x", "y").map();

        XContentBuilder builder = jsonBuilder().startObject();
        builder.field(HttpInput.Field.STATUS.getPreferredName(), 123);
        builder.field(HttpInput.Field.REQUEST.getPreferredName(), request);
        builder.field(HttpInput.Field.PAYLOAD.getPreferredName(), payload);
        builder.endObject();

        XContentParser parser = XContentHelper.createParser(builder.bytes());
        parser.nextToken();
        HttpInput.Result result = httpParser.parseResult("_id", parser);
        assertThat(result.type(), equalTo(HttpInput.TYPE));
        assertThat(result.payload().data(), equalTo(payload));
        assertThat(result.status(), equalTo(123));
        assertThat(result.request().method().method(), equalTo("GET"));
        assertThat(result.request().headers().size(), equalTo(headers.size()));
        assertThat(result.request().headers(), hasEntry("a", (Object) "b"));
        assertThat(result.request().host(), equalTo("_host"));
        assertThat(result.request().port(), equalTo(123));
        assertThat(result.request().body(), equalTo("_body"));
    }

}
