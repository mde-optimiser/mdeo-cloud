package com.mdeo.backend.git.ssh

import com.mdeo.common.model.User
import org.apache.sshd.common.AttributeRepository

/**
 * Session attribute [GitSshPublickeyAuthenticator] stores the resolved user
 * under, once public key authentication succeeds, for [GitSshCommandFactory]
 * to read back when the client's subsequent `exec` request arrives on the
 * same session.
 */
internal val AUTHENTICATED_USER_KEY = AttributeRepository.AttributeKey<User>()

/**
 * Session attribute holding the id of the key that authenticated the
 * session, so [GitSshCommandFactory] can record genuine use once a command
 * actually runs - which is necessarily after MINA has verified the
 * client's signature. See [com.mdeo.backend.service.SshKeyService.recordKeyUsed].
 */
internal val AUTHENTICATED_KEY_ID = AttributeRepository.AttributeKey<java.util.UUID>()
