package com.example.greetingcard.data
import com.hierynomus.smbj.SMBClient
import com.hierynomus.smbj.auth.AuthenticationContext
import com.hierynomus.smbj.connection.Connection
import com.hierynomus.smbj.session.Session

class Samba(hostname: String, username: String, password: String, domain: String) {
    private var connection: Connection
    private var authContext: AuthenticationContext
    private var session: Session

    init  {
        val client = SMBClient()
        this.connection = client.connect(hostname)
        this.authContext = AuthenticationContext(username, password.toCharArray(), domain)
        this.session = connection.authenticate(authContext)
    }

}