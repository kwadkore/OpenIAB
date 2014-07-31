/*
 * This file is auto-generated.  DO NOT MODIFY.
 * Original file: /Users/kweku/Documents/Programming/Android/OpenIAB/library/src/org/onepf/oms/IOpenAppstore.aidl
 */
package org.onepf.oms;
/**
 * Service interface to implement by OpenStore implementation
 * 
 * @author Boris Minaev, Oleg Orlov
 * @since 29.04.2013
 */
public interface IOpenAppstore extends android.os.IInterface
{
/** Local-side IPC implementation stub class. */
public static abstract class Stub extends android.os.Binder implements org.onepf.oms.IOpenAppstore
{
private static final java.lang.String DESCRIPTOR = "org.onepf.oms.IOpenAppstore";
/** Construct the stub at attach it to the interface. */
public Stub()
{
this.attachInterface(this, DESCRIPTOR);
}
/**
 * Cast an IBinder object into an org.onepf.oms.IOpenAppstore interface,
 * generating a proxy if needed.
 */
public static org.onepf.oms.IOpenAppstore asInterface(android.os.IBinder obj)
{
if ((obj==null)) {
return null;
}
android.os.IInterface iin = obj.queryLocalInterface(DESCRIPTOR);
if (((iin!=null)&&(iin instanceof org.onepf.oms.IOpenAppstore))) {
return ((org.onepf.oms.IOpenAppstore)iin);
}
return new org.onepf.oms.IOpenAppstore.Stub.Proxy(obj);
}
@Override public android.os.IBinder asBinder()
{
return this;
}
@Override public boolean onTransact(int code, android.os.Parcel data, android.os.Parcel reply, int flags) throws android.os.RemoteException
{
switch (code)
{
case INTERFACE_TRANSACTION:
{
reply.writeString(DESCRIPTOR);
return true;
}
case TRANSACTION_getAppstoreName:
{
data.enforceInterface(DESCRIPTOR);
java.lang.String _result = this.getAppstoreName();
reply.writeNoException();
reply.writeString(_result);
return true;
}
case TRANSACTION_isPackageInstaller:
{
data.enforceInterface(DESCRIPTOR);
java.lang.String _arg0;
_arg0 = data.readString();
boolean _result = this.isPackageInstaller(_arg0);
reply.writeNoException();
reply.writeInt(((_result)?(1):(0)));
return true;
}
case TRANSACTION_isBillingAvailable:
{
data.enforceInterface(DESCRIPTOR);
java.lang.String _arg0;
_arg0 = data.readString();
boolean _result = this.isBillingAvailable(_arg0);
reply.writeNoException();
reply.writeInt(((_result)?(1):(0)));
return true;
}
case TRANSACTION_getPackageVersion:
{
data.enforceInterface(DESCRIPTOR);
java.lang.String _arg0;
_arg0 = data.readString();
int _result = this.getPackageVersion(_arg0);
reply.writeNoException();
reply.writeInt(_result);
return true;
}
case TRANSACTION_getBillingServiceIntent:
{
data.enforceInterface(DESCRIPTOR);
android.content.Intent _result = this.getBillingServiceIntent();
reply.writeNoException();
if ((_result!=null)) {
reply.writeInt(1);
_result.writeToParcel(reply, android.os.Parcelable.PARCELABLE_WRITE_RETURN_VALUE);
}
else {
reply.writeInt(0);
}
return true;
}
case TRANSACTION_getProductPageIntent:
{
data.enforceInterface(DESCRIPTOR);
java.lang.String _arg0;
_arg0 = data.readString();
android.content.Intent _result = this.getProductPageIntent(_arg0);
reply.writeNoException();
if ((_result!=null)) {
reply.writeInt(1);
_result.writeToParcel(reply, android.os.Parcelable.PARCELABLE_WRITE_RETURN_VALUE);
}
else {
reply.writeInt(0);
}
return true;
}
case TRANSACTION_getRateItPageIntent:
{
data.enforceInterface(DESCRIPTOR);
java.lang.String _arg0;
_arg0 = data.readString();
android.content.Intent _result = this.getRateItPageIntent(_arg0);
reply.writeNoException();
if ((_result!=null)) {
reply.writeInt(1);
_result.writeToParcel(reply, android.os.Parcelable.PARCELABLE_WRITE_RETURN_VALUE);
}
else {
reply.writeInt(0);
}
return true;
}
case TRANSACTION_getSameDeveloperPageIntent:
{
data.enforceInterface(DESCRIPTOR);
java.lang.String _arg0;
_arg0 = data.readString();
android.content.Intent _result = this.getSameDeveloperPageIntent(_arg0);
reply.writeNoException();
if ((_result!=null)) {
reply.writeInt(1);
_result.writeToParcel(reply, android.os.Parcelable.PARCELABLE_WRITE_RETURN_VALUE);
}
else {
reply.writeInt(0);
}
return true;
}
case TRANSACTION_areOutsideLinksAllowed:
{
data.enforceInterface(DESCRIPTOR);
boolean _result = this.areOutsideLinksAllowed();
reply.writeNoException();
reply.writeInt(((_result)?(1):(0)));
return true;
}
}
return super.onTransact(code, data, reply, flags);
}
private static class Proxy implements org.onepf.oms.IOpenAppstore
{
private android.os.IBinder mRemote;
Proxy(android.os.IBinder remote)
{
mRemote = remote;
}
@Override public android.os.IBinder asBinder()
{
return mRemote;
}
public java.lang.String getInterfaceDescriptor()
{
return DESCRIPTOR;
}
/**
     * Every OpenStore implementation must provide their name. It's required for core OpenIAB functions 
     */
@Override public java.lang.String getAppstoreName() throws android.os.RemoteException
{
android.os.Parcel _data = android.os.Parcel.obtain();
android.os.Parcel _reply = android.os.Parcel.obtain();
java.lang.String _result;
try {
_data.writeInterfaceToken(DESCRIPTOR);
mRemote.transact(Stub.TRANSACTION_getAppstoreName, _data, _reply, 0);
_reply.readException();
_result = _reply.readString();
}
finally {
_reply.recycle();
_data.recycle();
}
return _result;
}
/**
     * OpenStores must provide information about packages it installed. If OpenStore is installer 
     * and supports In-App billing it will be used for purchases
     */
@Override public boolean isPackageInstaller(java.lang.String packageName) throws android.os.RemoteException
{
android.os.Parcel _data = android.os.Parcel.obtain();
android.os.Parcel _reply = android.os.Parcel.obtain();
boolean _result;
try {
_data.writeInterfaceToken(DESCRIPTOR);
_data.writeString(packageName);
mRemote.transact(Stub.TRANSACTION_isPackageInstaller, _data, _reply, 0);
_reply.readException();
_result = (0!=_reply.readInt());
}
finally {
_reply.recycle();
_data.recycle();
}
return _result;
}
/**
     * If <b>true</b> OpenIAB assumes In-App items (SKU) for app are published and ready to use
     */
@Override public boolean isBillingAvailable(java.lang.String packageName) throws android.os.RemoteException
{
android.os.Parcel _data = android.os.Parcel.obtain();
android.os.Parcel _reply = android.os.Parcel.obtain();
boolean _result;
try {
_data.writeInterfaceToken(DESCRIPTOR);
_data.writeString(packageName);
mRemote.transact(Stub.TRANSACTION_isBillingAvailable, _data, _reply, 0);
_reply.readException();
_result = (0!=_reply.readInt());
}
finally {
_reply.recycle();
_data.recycle();
}
return _result;
}
/**
     * Provides android:versionCode of .apk published in OpenStore
     * @return -1 if UNDEFINED
     */
@Override public int getPackageVersion(java.lang.String packageName) throws android.os.RemoteException
{
android.os.Parcel _data = android.os.Parcel.obtain();
android.os.Parcel _reply = android.os.Parcel.obtain();
int _result;
try {
_data.writeInterfaceToken(DESCRIPTOR);
_data.writeString(packageName);
mRemote.transact(Stub.TRANSACTION_getPackageVersion, _data, _reply, 0);
_reply.readException();
_result = _reply.readInt();
}
finally {
_reply.recycle();
_data.recycle();
}
return _result;
}
/**
     * Should provide Intent to be used for binding IOpenInAppBillingService
     */
@Override public android.content.Intent getBillingServiceIntent() throws android.os.RemoteException
{
android.os.Parcel _data = android.os.Parcel.obtain();
android.os.Parcel _reply = android.os.Parcel.obtain();
android.content.Intent _result;
try {
_data.writeInterfaceToken(DESCRIPTOR);
mRemote.transact(Stub.TRANSACTION_getBillingServiceIntent, _data, _reply, 0);
_reply.readException();
if ((0!=_reply.readInt())) {
_result = android.content.Intent.CREATOR.createFromParcel(_reply);
}
else {
_result = null;
}
}
finally {
_reply.recycle();
_data.recycle();
}
return _result;
}
@Override public android.content.Intent getProductPageIntent(java.lang.String packageName) throws android.os.RemoteException
{
android.os.Parcel _data = android.os.Parcel.obtain();
android.os.Parcel _reply = android.os.Parcel.obtain();
android.content.Intent _result;
try {
_data.writeInterfaceToken(DESCRIPTOR);
_data.writeString(packageName);
mRemote.transact(Stub.TRANSACTION_getProductPageIntent, _data, _reply, 0);
_reply.readException();
if ((0!=_reply.readInt())) {
_result = android.content.Intent.CREATOR.createFromParcel(_reply);
}
else {
_result = null;
}
}
finally {
_reply.recycle();
_data.recycle();
}
return _result;
}
@Override public android.content.Intent getRateItPageIntent(java.lang.String packageName) throws android.os.RemoteException
{
android.os.Parcel _data = android.os.Parcel.obtain();
android.os.Parcel _reply = android.os.Parcel.obtain();
android.content.Intent _result;
try {
_data.writeInterfaceToken(DESCRIPTOR);
_data.writeString(packageName);
mRemote.transact(Stub.TRANSACTION_getRateItPageIntent, _data, _reply, 0);
_reply.readException();
if ((0!=_reply.readInt())) {
_result = android.content.Intent.CREATOR.createFromParcel(_reply);
}
else {
_result = null;
}
}
finally {
_reply.recycle();
_data.recycle();
}
return _result;
}
@Override public android.content.Intent getSameDeveloperPageIntent(java.lang.String packageName) throws android.os.RemoteException
{
android.os.Parcel _data = android.os.Parcel.obtain();
android.os.Parcel _reply = android.os.Parcel.obtain();
android.content.Intent _result;
try {
_data.writeInterfaceToken(DESCRIPTOR);
_data.writeString(packageName);
mRemote.transact(Stub.TRANSACTION_getSameDeveloperPageIntent, _data, _reply, 0);
_reply.readException();
if ((0!=_reply.readInt())) {
_result = android.content.Intent.CREATOR.createFromParcel(_reply);
}
else {
_result = null;
}
}
finally {
_reply.recycle();
_data.recycle();
}
return _result;
}
@Override public boolean areOutsideLinksAllowed() throws android.os.RemoteException
{
android.os.Parcel _data = android.os.Parcel.obtain();
android.os.Parcel _reply = android.os.Parcel.obtain();
boolean _result;
try {
_data.writeInterfaceToken(DESCRIPTOR);
mRemote.transact(Stub.TRANSACTION_areOutsideLinksAllowed, _data, _reply, 0);
_reply.readException();
_result = (0!=_reply.readInt());
}
finally {
_reply.recycle();
_data.recycle();
}
return _result;
}
}
static final int TRANSACTION_getAppstoreName = (android.os.IBinder.FIRST_CALL_TRANSACTION + 0);
static final int TRANSACTION_isPackageInstaller = (android.os.IBinder.FIRST_CALL_TRANSACTION + 1);
static final int TRANSACTION_isBillingAvailable = (android.os.IBinder.FIRST_CALL_TRANSACTION + 2);
static final int TRANSACTION_getPackageVersion = (android.os.IBinder.FIRST_CALL_TRANSACTION + 3);
static final int TRANSACTION_getBillingServiceIntent = (android.os.IBinder.FIRST_CALL_TRANSACTION + 4);
static final int TRANSACTION_getProductPageIntent = (android.os.IBinder.FIRST_CALL_TRANSACTION + 5);
static final int TRANSACTION_getRateItPageIntent = (android.os.IBinder.FIRST_CALL_TRANSACTION + 6);
static final int TRANSACTION_getSameDeveloperPageIntent = (android.os.IBinder.FIRST_CALL_TRANSACTION + 7);
static final int TRANSACTION_areOutsideLinksAllowed = (android.os.IBinder.FIRST_CALL_TRANSACTION + 8);
}
/**
     * Every OpenStore implementation must provide their name. It's required for core OpenIAB functions 
     */
public java.lang.String getAppstoreName() throws android.os.RemoteException;
/**
     * OpenStores must provide information about packages it installed. If OpenStore is installer 
     * and supports In-App billing it will be used for purchases
     */
public boolean isPackageInstaller(java.lang.String packageName) throws android.os.RemoteException;
/**
     * If <b>true</b> OpenIAB assumes In-App items (SKU) for app are published and ready to use
     */
public boolean isBillingAvailable(java.lang.String packageName) throws android.os.RemoteException;
/**
     * Provides android:versionCode of .apk published in OpenStore
     * @return -1 if UNDEFINED
     */
public int getPackageVersion(java.lang.String packageName) throws android.os.RemoteException;
/**
     * Should provide Intent to be used for binding IOpenInAppBillingService
     */
public android.content.Intent getBillingServiceIntent() throws android.os.RemoteException;
public android.content.Intent getProductPageIntent(java.lang.String packageName) throws android.os.RemoteException;
public android.content.Intent getRateItPageIntent(java.lang.String packageName) throws android.os.RemoteException;
public android.content.Intent getSameDeveloperPageIntent(java.lang.String packageName) throws android.os.RemoteException;
public boolean areOutsideLinksAllowed() throws android.os.RemoteException;
}
