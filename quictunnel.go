// Package quictunnel provides a QUIC-based TCP tunnel.
// Client (app): local TCP listener → QUIC stream → server
// Server (OnePlus): QUIC listener → TCP 127.0.0.1:5555
package quictunnel

import (
	"context"
	"crypto/ecdsa"
	"crypto/elliptic"
	"crypto/rand"
	"crypto/tls"
	"crypto/x509"
	"crypto/x509/pkix"
	"encoding/pem"
	"fmt"
	"io"
	"log"
	"math/big"
	"net"
	"time"

	"github.com/quic-go/quic-go"
)

// ---- Client side (app, via gomobile/JNI) ----

var clientListener net.Listener

// StartClient starts a local TCP listener that forwards connections over QUIC.
// Returns the local port as string, or "ERROR:reason".
//
//export StartClient
func StartClient(serverAddr string, localPort int, psk string) string {
	ln, err := net.Listen("tcp", fmt.Sprintf("127.0.0.1:%d", localPort))
	if err != nil {
		return "ERROR:" + err.Error()
	}
	clientListener = ln
	port := ln.Addr().(*net.TCPAddr).Port
	log.Printf("QUIC client: 127.0.0.1:%d -> %s", port, serverAddr)

	tlsConf := &tls.Config{
		InsecureSkipVerify: true,
		NextProtos:         []string{"scrcpy-tunnel"},
	}
	quicConf := &quic.Config{
		MaxIdleTimeout:                 15 * time.Second,
		KeepAlivePeriod:                5 * time.Second,
		InitialStreamReceiveWindow:     4 * 1024 * 1024,  // 4MB
		InitialConnectionReceiveWindow: 8 * 1024 * 1024,  // 8MB
		MaxIncomingStreams:             10,
		DisablePathMTUDiscovery:        false,
	}

	go func() {
		for {
			conn, err := ln.Accept()
			if err != nil {
				return
			}
			go handleClientConn(conn, serverAddr, tlsConf, quicConf, psk)
		}
	}()

	return fmt.Sprintf("%d", port)
}

func handleClientConn(tcpConn net.Conn, serverAddr string, tlsConf *tls.Config, quicConf *quic.Config, psk string) {
	defer tcpConn.Close()

	ctx, cancel := context.WithTimeout(context.Background(), 10*time.Second)
	defer cancel()

	qconn, err := quic.DialAddr(ctx, serverAddr, tlsConf, quicConf)
	if err != nil {
		log.Printf("QUIC dial %s: %v", serverAddr, err)
		return
	}
	defer qconn.CloseWithError(0, "done")

	stream, err := qconn.OpenStreamSync(context.Background())
	if err != nil {
		log.Printf("QUIC open stream: %v", err)
		return
	}
	defer stream.Close()

	// Auth
	stream.Write([]byte(fmt.Sprintf("AUTH:%s\n", psk)))
	resp := make([]byte, 1)
	if _, err := io.ReadFull(stream, resp); err != nil || resp[0] != 'O' {
		log.Printf("QUIC auth failed")
		return
	}

	log.Printf("QUIC tunnel established")

	// Bidirectional copy
	done := make(chan struct{}, 2)
	go func() { io.Copy(stream, tcpConn); stream.CancelWrite(0); done <- struct{}{} }()
	go func() { io.Copy(tcpConn, stream); tcpConn.(*net.TCPConn).CloseWrite(); done <- struct{}{} }()
	<-done
}

// StopClient stops the client listener.
//
//export StopClient
func StopClient() {
	if clientListener != nil {
		clientListener.Close()
		clientListener = nil
	}
}

// ---- Server side (OnePlus, standalone binary) ----

// StartServer starts a QUIC server forwarding to targetAddr.
//
//export StartServer
func StartServer(listenAddr, targetAddr, psk string) string {
	cert, err := generateSelfSignedCert()
	if err != nil {
		return "ERROR:cert:" + err.Error()
	}
	tlsConf := &tls.Config{
		Certificates: []tls.Certificate{cert},
		NextProtos:   []string{"scrcpy-tunnel"},
	}
	quicConf := &quic.Config{
		MaxIdleTimeout:                 15 * time.Second,
		KeepAlivePeriod:                5 * time.Second,
		InitialStreamReceiveWindow:     4 * 1024 * 1024,
		InitialConnectionReceiveWindow: 8 * 1024 * 1024,
		MaxIncomingStreams:             10,
		DisablePathMTUDiscovery:        false,
	}

	ln, err := quic.ListenAddr(listenAddr, tlsConf, quicConf)
	if err != nil {
		return "ERROR:listen:" + err.Error()
	}
	log.Printf("QUIC server on %s -> %s", listenAddr, targetAddr)

	go func() {
		for {
			conn, err := ln.Accept(context.Background())
			if err != nil {
				log.Printf("QUIC accept: %v", err)
				continue
			}
			go handleServerConn(conn, targetAddr, psk)
		}
	}()

	return "OK"
}

func handleServerConn(qconn *quic.Conn, targetAddr, psk string) {
	defer qconn.CloseWithError(0, "done")

	stream, err := qconn.AcceptStream(context.Background())
	if err != nil {
		return
	}
	defer stream.Close()

	// Read auth
	buf := make([]byte, 256)
	n, err := stream.Read(buf)
	if err != nil {
		return
	}
	authLine := string(buf[:n])
	expected := fmt.Sprintf("AUTH:%s", psk)
	if len(authLine) < len(expected) || authLine[:len(expected)] != expected {
		log.Printf("QUIC server: auth failed")
		stream.Write([]byte{'F'})
		return
	}
	stream.Write([]byte{'O'})

	log.Printf("QUIC server: auth OK -> %s", targetAddr)

	tcpConn, err := net.Dial("tcp", targetAddr)
	if err != nil {
		log.Printf("QUIC server: dial %s: %v", targetAddr, err)
		return
	}
	defer tcpConn.Close()

	done := make(chan struct{}, 2)
	go func() { io.Copy(tcpConn, stream); tcpConn.(*net.TCPConn).CloseWrite(); done <- struct{}{} }()
	go func() { io.Copy(stream, tcpConn); stream.CancelWrite(0); done <- struct{}{} }()
	<-done
}

func generateSelfSignedCert() (tls.Certificate, error) {
	priv, err := ecdsa.GenerateKey(elliptic.P256(), rand.Reader)
	if err != nil {
		return tls.Certificate{}, err
	}
	tmpl := x509.Certificate{
		SerialNumber: big.NewInt(1),
		Subject:      pkix.Name{CommonName: "scrcpy-tunnel"},
		NotBefore:    time.Now(),
		NotAfter:     time.Now().Add(365 * 24 * time.Hour),
		KeyUsage:     x509.KeyUsageDigitalSignature,
	}
	certDER, err := x509.CreateCertificate(rand.Reader, &tmpl, &tmpl, &priv.PublicKey, priv)
	if err != nil {
		return tls.Certificate{}, err
	}
	certPEM := pem.EncodeToMemory(&pem.Block{Type: "CERTIFICATE", Bytes: certDER})
	keyPEM, err := x509.MarshalECPrivateKey(priv)
	if err != nil {
		return tls.Certificate{}, err
	}
	keyPEMBlock := pem.EncodeToMemory(&pem.Block{Type: "EC PRIVATE KEY", Bytes: keyPEM})
	return tls.X509KeyPair(certPEM, keyPEMBlock)
}
