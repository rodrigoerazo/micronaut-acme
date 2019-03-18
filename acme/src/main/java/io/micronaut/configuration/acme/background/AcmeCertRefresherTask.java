/*
 * Copyright 2017-2019 original authors
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package io.micronaut.configuration.acme.background;

import io.micronaut.configuration.acme.events.CertificateEvent;
import io.micronaut.context.annotation.Property;
import io.micronaut.context.annotation.Value;
import io.micronaut.context.event.ApplicationEventPublisher;
import io.micronaut.scheduling.annotation.Scheduled;
import org.shredzone.acme4j.*;
import org.shredzone.acme4j.challenge.Challenge;
import org.shredzone.acme4j.exception.AcmeException;
import org.shredzone.acme4j.util.CSRBuilder;
import org.shredzone.acme4j.util.KeyPairUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.inject.Inject;
import javax.inject.Singleton;
import java.io.*;
import java.security.KeyPair;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * Background task to automatically refresh the certificates from an ACME server on a configurable interval.
 */
@Singleton
public final class AcmeCertRefresherTask {

    private static final Logger LOG = LoggerFactory.getLogger(AcmeCertRefresherTask.class);

    @Inject
    ApplicationEventPublisher eventPublisher;


    @Value("${micronaut.ssl.acme.renew.within:30}")
    protected int renewWithinDays = 30;

    @Value("${micronaut.ssl.acme.tos.agree:false}")
    protected boolean agreeToTOS = false;

    /**
     * Let's Encrypt has different production vs test servers.
     * <p>
     * Production : acme://letsencrypt.org
     * Staging/Test : acme://letsencrypt.org/staging
     * <p>
     * To note : Java 8u101 or higher is required for connecting to the Let’s Encrypt servers.
     * <p>
     * see here https://shredzone.org/maven/acme4j/ca/letsencrypt.html
     */
    @Property(name = "micronaut.ssl.acme.server.url")
    protected String acmeServerUrl;

    @Property(name = "micronaut.ssl.acme.cert.output.path")
    protected String certPath;

    @Property(name = "micronaut.ssl.acme.domain.keypair")
    protected String domainKeyPairString;

    @Property(name = "micronaut.ssl.acme.account.keypair")
    protected String keyPairString;

    @Property(name = "micronaut.ssl.acme.domain")
    protected String domain;

    @Value("${micronaut.ssl.acme.auth.retry.attempts:10}")
    protected int authRetryAttempts;

    @Value("${micronaut.ssl.acme.auth.pause.ms:3000}")
    protected long authPauseMs;

    @Value("${micronaut.ssl.acme.order.retry.attempts:10}")
    protected int orderRetryAttempts;

    @Value("${micronaut.ssl.acme.order.pause.ms:3000}")
    protected long orderPauseMs;


    /**
     * Schedule task to refresh certs from ACME server.
     */
    @Scheduled(
            fixedDelay = "${micronaut.ssl.acme.refresh.frequency:24h}",
            initialDelay = "${micronaut.ssl.acme.refresh.delay:1m}")
    void renewCertIfNeeded() {
        if (!agreeToTOS) {
            throw new IllegalStateException("Cannot refresh certificates until terms of service is accepted. Please review the TOS for Let's Encrypt and place this property in your configuration once complete : 'micronaut.ssl.acme.tos.agree = true'");
        }

        List<String> domains = new ArrayList<>();
        domains.add(domain);
        if (isWildcardDomain()) {
            LOG.debug("Wildcard domain found, as per ACME4j spec we must include the wildcard domain and the base domain name in the order details.");
            domains.add(domain.replace("*.", ""));
        }

        try {
            Session session = new Session(acmeServerUrl);

            KeyPair accountKeyPair = KeyPairUtils.readKeyPair(new StringReader(keyPairString));

            Login login = new AccountBuilder()
                    .onlyExisting()
                    .useKeyPair(accountKeyPair)
                    .createLogin(session);


            Order order = login.getAccount()
                    .newOrder()
                    .domains(domains)
                    .create();


            for (Authorization auth : order.getAuthorizations()) {
                authorize(auth);
            }

            // Generate a CSR for all of the domains, and sign it with the domain key pair.
            KeyPair domainKeyPair = KeyPairUtils.readKeyPair(new StringReader(domainKeyPairString));
            CSRBuilder csrb = new CSRBuilder();
            csrb.addDomains(domains);
            csrb.sign(domainKeyPair);

            // Write the CSR to a file, for later use.
            File domainCsr = new File(certPath, "domain.csr");
            try (Writer out = new FileWriter(domainCsr)) {
                csrb.write(out);
            }

            // Order the certificate
            order.execute(csrb.getEncoded());

            // Wait for the order to complete
            try {
                while (order.getStatus() != Status.VALID && orderRetryAttempts-- > 0) {
                    // Did the order fail?
                    if (order.getStatus() == Status.INVALID) {
                        throw new AcmeException("Order failed... Giving up.");
                    }

                    // Wait for a few seconds
                    Thread.sleep(orderPauseMs);

                    // Then update the status
                    order.update();
                }
            } catch (InterruptedException ex) {
                LOG.error("interrupted", ex);
                Thread.currentThread().interrupt();
            }

            // Get the certificate
            Certificate certificate = order.getCertificate();

            LOG.info("Success! The certificate for domain {} has been generated!", domain);
            LOG.info("Certificate URL: {}", certificate.getLocation());

            // Write a combined file containing the certificate and chain.
            File certificateFile = new File(certPath, "domain.crt");
            try (Writer fw = new FileWriter(certificateFile)) {
                certificate.writeCertificate(fw);
            }

            eventPublisher.publishEvent(new CertificateEvent(certificateFile, domainKeyPair));

        } catch (IOException e) {
            LOG.error("Failed to parse key pair", e);
        } catch (AcmeException e) {
            LOG.error("Failed to communicate with Acme server", e);
        }
    }

    private boolean isWildcardDomain() {
        return domain.startsWith("*.");
    }

    /**
     * Authorize a domain. It will be associated with your account, so you will be able to
     * retrieve a signed certificate for the domain later.
     *
     * @param auth {@link Authorization} to perform
     */
    private void authorize(Authorization auth) throws AcmeException {
        LOG.info("Authorization {} for domain {}", auth, auth.getIdentifier().getDomain());

        // The authorization is already valid. No need to process a challenge.
        if (auth.getStatus() == Status.VALID) {
            return;
        }

        Optional<Challenge> validChallenge = auth.getChallenges().stream().filter(c -> c.getStatus() == Status.VALID).findFirst();

        if (validChallenge.isPresent()) {
            return;
        }

        for (Challenge challenge : auth.getChallenges()) {
            // Now trigger the challenge.
            challenge.trigger();

            // Poll for the challenge to complete.
            try {
                while (challenge.getStatus() != Status.VALID && authRetryAttempts-- > 0) {
                    // Did the authorization fail?
                    if (challenge.getStatus() == Status.INVALID) {
                        throw new AcmeException("Challenge of type " + challenge.getType() + "failed. With error : " + challenge.getError() + ", for domain" + auth.getIdentifier().toString() + " ... Giving up.");
                    }

                    // Wait for a few seconds
                    Thread.sleep(authPauseMs);

                    // Then update the status
                    challenge.update();
                }
                break;
            } catch (InterruptedException ex) {
                LOG.error("", ex);
                Thread.currentThread().interrupt();
            }

            // All reattempts are used up and there is still no valid authorization?
            if (challenge.getStatus() != Status.VALID) {
                throw new AcmeException("Failed to pass the challenge for domain "
                        + auth.getIdentifier().toString() + ", ... Giving up.");
            }
            LOG.info("Challenge has been completed for domain : " + auth.getIdentifier().toString() + ".");
        }

    }

}

