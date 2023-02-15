package com.examples.experiment;

import software.amazon.awssdk.auth.credentials.*;
import software.amazon.awssdk.auth.signer.Aws4Signer;
import software.amazon.awssdk.auth.signer.params.Aws4SignerParams;
import software.amazon.awssdk.http.*;
import software.amazon.awssdk.services.sts.StsClient;
import software.amazon.awssdk.http.urlconnection.UrlConnectionHttpClient;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.sts.model.AssumeRoleRequest;
import software.amazon.awssdk.services.sts.model.AssumeRoleResponse;
import software.amazon.awssdk.services.sts.model.Credentials;
import software.amazon.awssdk.services.sts.model.StsException;
import org.apache.commons.io.IOUtils;

import java.io.InputStream;
import java.net.URI;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.time.format.FormatStyle;
import java.util.Locale;


/**
 * Simple example to execute API call to AWS via APIGW
 * Including example on how to use Aws4Signer library
 */
public class SignerExperiment {

    public static void main(String[] args) throws Exception {

        SdkHttpClient httpClient = UrlConnectionHttpClient.builder().build();
        // get Signer
        Aws4Signer signer = Aws4Signer.create();
        String region = "us-west-2"; // for servers located in HK

        // for aws credentials, you could use the default one
        AwsCredentialsProvider credProvider = DefaultCredentialsProvider.create();

        // or use sts assume role
        System.out.println("building sts client");
        System.out.println(credProvider.resolveCredentials());

        String roleToAssume = "<replace-me>";
        String roleSessionName = "test-session-1";
        StsClient stsClient = StsClient.builder()
                .region(Region.of(region))
                .credentialsProvider(credProvider)
                .httpClient(httpClient)
                .build();

        AwsSessionCredentials creds = assumeGivenRole(stsClient, roleToAssume, roleSessionName);

        stsClient.close();

        System.out.println(credProvider.resolveCredentials());

        System.out.println("sts assumed credentials " + creds);

        // request configs
        // url for PDX beta, DO NOT SHARE
        String url = "<replace-me>";
        URL urlObj = new URL(url);
        String serviceName = "execute-api";
        SdkHttpMethod method = SdkHttpMethod.POST;
        String body = "{\"string\": \"hello\"}";

        Aws4SignerParams params = Aws4SignerParams.builder()
                .signingRegion(Region.of(region))
                .awsCredentials(creds)
                .signingName(serviceName)
                .doubleUrlEncode(true)
                .build();

        SdkHttpFullRequest request = SdkHttpFullRequest.builder()
                .method(method)
                .uri(URI.create(url))
                .protocol("https")
                .contentStreamProvider(() -> IOUtils.toInputStream(body, StandardCharsets.UTF_8))
                .build();

        System.out.println(request.host());
        System.out.println(request.encodedPath());
        System.out.println(request.headers());
        System.out.println("signing\n");

        SdkHttpFullRequest signedRequest = signer.sign(request, params);
        System.out.println(signedRequest.host());
        System.out.println(signedRequest.encodedPath());
        System.out.println(signedRequest.headers());
        // make the request
        try {
            System.out.println("calling API endpoint\n...\n...\n");
            SdkHttpClient client = httpClient;

            HttpExecuteRequest executeRequest = HttpExecuteRequest.builder()
                    .request(signedRequest)
                    .contentStreamProvider(signedRequest.contentStreamProvider().orElse(null))
                    .build();
            HttpExecuteResponse response = client.prepareRequest(executeRequest).call();
            InputStream responseStream = response.responseBody()
                    .orElseThrow(() -> new IllegalStateException("Did not receive response from API"));

            /*
                expect to see response code 200
                200
                Optional[OK]
                {"string":"hello"}
             */
            System.out.println(response.httpResponse().statusCode());
            System.out.println(response.httpResponse().statusText());
            System.out.println(IOUtils.toString(responseStream, StandardCharsets.UTF_8));


        } catch (Exception e) {
            e.printStackTrace();
        } finally {
            httpClient.close();
        }
    }


    public static AwsSessionCredentials assumeGivenRole(StsClient stsClient, String roleArn, String roleSessionName) {

        AwsSessionCredentials result = null;
        try {
            AssumeRoleRequest roleRequest = AssumeRoleRequest.builder()
                    .roleArn(roleArn)
                    .roleSessionName(roleSessionName)
                    .build();

            AssumeRoleResponse roleResponse = stsClient.assumeRole(roleRequest);
            Credentials myCreds = roleResponse.credentials();
            result = AwsSessionCredentials.create(myCreds.accessKeyId(), myCreds.secretAccessKey(), myCreds.sessionToken());
            // Display the time when the temp creds expire.
            Instant exTime = myCreds.expiration();
            String tokenInfo = myCreds.sessionToken();

            // Convert the Instant to readable date.
            DateTimeFormatter formatter =
                    DateTimeFormatter.ofLocalizedDateTime(FormatStyle.SHORT)
                            .withLocale(Locale.US)
                            .withZone(ZoneId.systemDefault());

            formatter.format(exTime);
            System.out.println("The token " + tokenInfo + "  expires on " + exTime);

        } catch (StsException e) {
            System.err.println(e.getMessage());
            System.exit(1);
        }
        return result;
    }
}