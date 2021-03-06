package services.platino;

import java.io.IOException;
import java.io.StringReader;
import java.net.URL;
import java.text.DateFormat;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.GregorianCalendar;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import javax.activation.DataSource;
import javax.inject.Inject;
import javax.persistence.EntityTransaction;
import javax.xml.datatype.DatatypeConfigurationException;
import javax.xml.datatype.DatatypeFactory;
import javax.xml.datatype.XMLGregorianCalendar;
import javax.xml.parsers.DocumentBuilder;
import javax.xml.parsers.DocumentBuilderFactory;
import javax.xml.ws.BindingProvider;

import org.apache.log4j.Logger;
import org.joda.time.DateTime;
import org.w3c.dom.Element;
import org.xml.sax.InputSource;
import org.apache.cxf.endpoint.Client;
import org.apache.cxf.frontend.ClientProxy;
import org.apache.cxf.transport.http.HTTPConduit;
import org.apache.cxf.transports.http.configuration.HTTPClientPolicy;

import com.sun.corba.se.impl.protocol.giopmsgheaders.Message;

import controllers.fap.AgenteController;
import emails.Mails;
import es.gobcan.eadmon.aed.ws.AedExcepcion;
import es.gobcan.eadmon.gestordocumental.ws.gestionelementos.dominio.Firma;
import es.gobcan.eadmon.gestordocumental.ws.gestionelementos.dominio.Firmante;
import es.gobcan.eadmon.gestordocumental.ws.gestionelementos.dominio.PropiedadesAdministrativas;
import es.gobcan.eadmon.gestordocumental.ws.gestionelementos.dominio.PropiedadesDocumento;
import es.gobcan.platino.servicios.registro.Asunto;
import es.gobcan.platino.servicios.registro.Documentos;
import es.gobcan.platino.servicios.registro.JustificanteRegistro;
import es.gobcan.platino.servicios.registro.Registro;
import es.gobcan.platino.servicios.registro.Registro_Service;
import platino.DatosDocumento;
import platino.DatosExpediente;
import platino.DatosFirmante;
import platino.DatosRegistro;
import platino.FirmaClient;
import platino.KeystoreCallbackHandler;
import platino.PlatinoCXFSecurityHeaders;
import platino.PlatinoProxy;
import platino.PlatinoSecurityUtils;
import play.db.jpa.JPA;
import play.modules.guice.InjectSupport;
import properties.FapProperties;
import properties.PropertyPlaceholder;
import services.GestorDocumentalService;
import services.FirmaService;
import services.GestorDocumentalServiceException;
import services.RegistroServiceException;
import services.RegistroService;
import utils.BinaryResponse;
import utils.CharsetUtils;
import utils.WSUtils;
import utils.WSUtils;
import messages.Messages;
import models.Aportacion;
import models.Documento;
import models.ExpedientePlatino;
import models.ObligatoriedadDocumentos;
import models.Persona;
import models.PersonaFisica;
import models.PersonaJuridica;
import models.SemillaExpediente;
import models.Singleton;
import models.Solicitante;
import models.SolicitudGenerica;
import models.TableKeyValue;

/**
 * RegistroServiceImpl
 * 
 * El servicio esta preparado para inicializarse de forma lazy.
 * Por lo tanto siempre que se vaya a consumir el servicio web
 * se deberia acceder a "getRegistroPort" en lugar de acceder directamente
 * a la property
 * 
 */
@InjectSupport
public class PlatinoRegistroServiceImpl implements RegistroService {

	private static Logger log = Logger.getLogger(RegistroService.class);

	private final PropertyPlaceholder propertyPlaceholder;

	private final Registro registroPort;

	private final FirmaService firmaService;

	private final GestorDocumentalService gestorDocumentalService;
	
	private final PlatinoGestorDocumentalService platinoGestorDocumentalService;
	
	private final String USERNAME;
	private final String PASSWORD;
	private final String PASSWORD_ENC;
	private final String ALIAS;
	private final String ASUNTO;
	private final long UNIDAD_ORGANICA;
	private final String TIPO_TRANSPORTE;
	private final String URIPROCEDIMIENTO;
	
	@Inject
	public PlatinoRegistroServiceImpl(PropertyPlaceholder propertyPlaceholder, FirmaService firmaService, GestorDocumentalService gestorDocumentalService){
	    this.propertyPlaceholder = propertyPlaceholder;
	    this.firmaService = firmaService;
	    this.gestorDocumentalService = gestorDocumentalService;
	    
        USERNAME = FapProperties.get("fap.platino.registro.username");
        PASSWORD = FapProperties.get("fap.platino.registro.password");
        ALIAS = FapProperties.get("fap.platino.registro.aliasServidor");
        PASSWORD_ENC = encriptarPassword(PASSWORD);
        ASUNTO = FapProperties.get("fap.platino.registro.asunto");
        UNIDAD_ORGANICA = FapProperties.getLong("fap.platino.registro.unidadOrganica");
        TIPO_TRANSPORTE = FapProperties.get("fap.platino.registro.tipoTransporte");
        URIPROCEDIMIENTO = FapProperties.get("fap.platino.security.procedimiento.uri");
        
        URL wsdlURL = Registro_Service.class.getClassLoader().getResource("wsdl/registro.wsdl");
        registroPort = new Registro_Service(wsdlURL).getRegistroPort();
        WSUtils.configureEndPoint(registroPort, getEndPoint());
        
        Map<String, String> headers = null;
        
        if ((URIPROCEDIMIENTO != null) && (URIPROCEDIMIENTO.compareTo("undefined") != 0)) {
        	headers = new HashMap<String, String>();
        	headers.put("uriProcedimiento", URIPROCEDIMIENTO);		
        }
		
        WSUtils.configureSecurityHeaders(registroPort, propertyPlaceholder, headers);
        
        PlatinoProxy.setProxy(registroPort); 
        
        this.platinoGestorDocumentalService = new PlatinoGestorDocumentalService(propertyPlaceholder);
	}
	
	private String encriptarPassword(String password){
        try {
            return PlatinoSecurityUtils.encriptarPassword(password);
        } catch (Exception e) {
            throw new RuntimeException("Error encriptando la contraseña", e);
        }	    
	}
	
	protected Registro getRegistroPort(){
		return this.registroPort;
	}
	
	public boolean isConfigured(){
	    return hasConnection() && platinoGestorDocumentalService.hasConnection();
	}
	
	@Override
    public void mostrarInfoInyeccion() {
		if (isConfigured())
			play.Logger.info("El servicio de Registro ha sido inyectado con Platino y está operativo.");
		else
			play.Logger.info("El servicio de Registro ha sido inyectado con Platino y NO está operativo.");
    }
	
	public boolean hasConnection() {
		boolean hasConnection = false;
		try {
			hasConnection = getVersion() != null;
		} catch (Exception e) {
			log.info("RegistroServiceImpl no tiene conexión con " + getEndPoint());
		}
		return hasConnection;
	}

	private String getEndPoint() {
		return propertyPlaceholder.get("fap.platino.registro.url");
	}

	private String getVersion() {
		return registroPort.getVersion();
	}

	@Deprecated
	@Override
	public models.JustificanteRegistro registrarEntrada(Solicitante solicitante, Documento documento, ExpedientePlatino expediente) throws RegistroServiceException{
	    return registrarEntrada(solicitante, documento, expediente, null);
	}
	
	private String getUriPlatino (String xml){
		String resultado = null;
		try{
			String nodo = "<Inicio>" + xml + "</Inicio>";
			DocumentBuilderFactory dbf = DocumentBuilderFactory.newInstance();
		    dbf.setNamespaceAware(true);
		    DocumentBuilder db  = dbf.newDocumentBuilder();
		    org.w3c.dom.Document doc = db.parse(new InputSource(new StringReader(nodo)));
		    Element uri = (Element) doc.getElementsByTagName("Nombre").item(0);
		    System.out.println("Obtenida uri de Platino: " +  uri.getTextContent());
		    play.Logger.info("Obtenida uri de Platino: " +  uri.getTextContent());
		    resultado = uri.getTextContent();
		}
		catch(Exception e){
			System.out.println("No se ha podido obtener la uri de Platino");
			play.Logger.error("No se ha podido obtener la uri de Platino");
		}
		return resultado;
	}
		
	@Override
	public models.JustificanteRegistro registrarEntrada(Solicitante solicitante, Documento documento, ExpedientePlatino expediente, String descripcion) throws RegistroServiceException{
	    DatosRegistro datosRegistro = getDatosRegistro(solicitante, documento, expediente, descripcion);
	    // Para las pruebas con estos certificados
	    if (datosRegistro.getNumeroDocumento().equals("ESA99999999")
	    		|| datosRegistro.getNumeroDocumento().equals("A99999999")) {
	    	datosRegistro.setNumeroDocumento("A99999997");
	    }
	    log.info("Llamando al getDatosRegistroNormalizados");
	    String datos = getDatosRegistroNormalizados(expediente, datosRegistro, documento);
	    log.info("Llamando a firmarDatosRegistro");
	    String datosFirmados = firmarDatosRegistro(datos);
        JustificanteRegistro justificantePlatino = registroDeEntrada(datos, datosFirmados);
        models.JustificanteRegistro justificante = getJustificanteRegistroModel(justificantePlatino);
        log.info("Realizando un Registro de Entrada: " +
        		"Agente: "+AgenteController.getAgente().name+
        		"Número de Registro: "+justificante.getNumeroRegistro()+
        		"Fecha: "+justificante.getFechaRegistro());
        String uriPlatino = getUriPlatino(datos);
        documento.uriPlatino = uriPlatino;
        documento.save();
        return justificante;
	}
	
    private DatosRegistro getDatosRegistro(Solicitante solicitante, Documento documento, ExpedientePlatino expediente, String descripcion) throws RegistroServiceException {
        XMLGregorianCalendar fechaApertura = WSUtils.getXmlGregorianCalendar(expediente.fechaApertura); 
        log.info("[getDatosRegistro] Obteniendo los datos de registro para el expediente: "+expediente.ruta);
        // Rellenamos datos expediente
        DatosExpediente datosExp = new DatosExpediente();
        datosExp.setNumero(expediente.numero);
        datosExp.setFechaApertura(fechaApertura);
        datosExp.setCreadoPlatino(expediente.getCreado());
        datosExp.setRuta(expediente.ruta);

        // Rellenamos datos Documento
        DatosDocumento datosDoc = new DatosDocumento();
        datosDoc.setTipoDoc("SOL");
        datosDoc.setTipoMime("application/pdf");

        if (documento.descripcionVisible == null)
            throw new NullPointerException();
        
        if (descripcion == null)
        	descripcion=documento.descripcionVisible;
        	// Añadimos RE al asunto en los registros de entrada

        datosDoc.setDescripcion(descripcion);
        datosDoc.setFecha(fechaApertura);

        try {
            models.Firma firma = gestorDocumentalService.getFirma(documento);
            if (firma != null){
            	log.info("[getDatosRegistro] Poniendo firma");
                datosDoc.setFirma(firma);
            }else{
                play.Logger.info("El documento no está firmado");
            }
        }catch(Exception e){
            throw new RegistroServiceException("Error recuperando la firma del documento", e);
        }

        try {
            BinaryResponse contentResponse = gestorDocumentalService.getDocumento(documento);
            DataSource dataSource = contentResponse.contenido.getDataSource();
            datosDoc.setContenido(dataSource);
        }catch(Exception e){
            throw new RegistroServiceException("Error recuperando el documento del gestor documental", e);
        }
        
        
        log.info("[getDatosRegistro] Contenido del documento obtenido");
        DatosRegistro datosRegistro = new DatosRegistro();
        datosRegistro.setExpediente(datosExp);
        datosRegistro.setDocumento(datosDoc);

        if (solicitante.isPersonaFisica()) {
            PersonaFisica solFisica = solicitante.fisica;
            datosRegistro.setNombreRemitente(solFisica.getNombreCompleto());
            datosRegistro.setNumeroDocumento(solFisica.nip.valor);
            datosRegistro.setTipoDocumento(solFisica.nip.getPlatinoTipoDocumento());
        } else if (solicitante.isPersonaJuridica()) {
            PersonaJuridica solJuridica = solicitante.juridica;
            datosRegistro.setNombreRemitente(solJuridica.entidad);
            datosRegistro.setNumeroDocumento(solJuridica.cif);
            datosRegistro.setTipoDocumento("C");// CIF
        }
        log.info("[getDatosRegistro] Datos de Registro obtenidos correctamente");
        return datosRegistro;
    }
    
    /**
     * 
     * @param expedientePlatino
     * @param datosRegistro
     * @return
     * @throws RegistroServiceException
     */
	
    private String getDatosRegistroNormalizados(ExpedientePlatino expedientePlatino, DatosRegistro datosRegistro, Documento documento) throws RegistroServiceException {
        log.info("[getDatosRegistroNormalizados] Ruta expediente " + datosRegistro.getExpediente().getRuta());
        
        crearExpedienteSiNoExiste(expedientePlatino);
        
        // Documento que se va a registrar
        es.gobcan.platino.servicios.registro.Documento doc = insertarDocumentoGestorDocumentalPlatino(expedientePlatino, datosRegistro.getDocumento(), documento);
        Documentos documentosRegistrar = new Documentos();
        documentosRegistrar.getDocumento().add(doc);
              
        // 3) Normalizamos los datos
        // Interesado: IP
        String nombre = datosRegistro.getNombreRemitente();
        String numeroDocumento = datosRegistro.getNumeroDocumento();

        // Se ha de indicar porque si no pone NIF por defecto
        String tipoDocumento = datosRegistro.getTipoDocumento();

        // Poner fecha en la que se produce la solicitud
        XMLGregorianCalendar fecha = WSUtils.getXmlGregorianCalendar(new Date()); 
        Asunto asunto = new Asunto();
        String asuntoProperty = datosRegistro.getAsunto();
		if (asuntoProperty == null) {
			asuntoProperty = ASUNTO;
		}
        asunto.getContent().add(asuntoProperty);
        
        Long organismo = UNIDAD_ORGANICA;
        if (datosRegistro.getUnidadOrganica() != null)
        	organismo = Long.valueOf(datosRegistro.getUnidadOrganica());

        //El valor de este campo se toma por defecto de la property si estuviese definida
        String tipoTransporte;
        if ((TIPO_TRANSPORTE.compareTo("undefined") != 0) && (TIPO_TRANSPORTE != null)){
        	tipoTransporte = TIPO_TRANSPORTE;
        } else
        	tipoTransporte = null;
        
        log.info("[getDatosRegistroNormalizados] normalizando datos firmados");
        try {
            String datosAFirmar = registroPort.normalizaDatosFirmados(
            		organismo, // Organismo
                    asunto, // Asunto
                    nombre, // Nombre remitente
                    tipoTransporte, // TipoTransporte (opcional)
                    tipoDocumento, // TipoDocumento (opcional)
                    numeroDocumento, // NIF remitente
                    fecha, // Fecha en la que se produce la solicitud
                    documentosRegistrar);
            datosAFirmar = CharsetUtils.fromISO2UTF8(datosAFirmar);
            log.info("[getDatosRegistroNormalizados] datos firmados normalizados correctamente");
            return datosAFirmar;
        } catch (Exception e) {
            log.error("Error normalizando los datos " + e.getMessage());
            log.error("getDatosRegistroNormalizados -> EXIT ERROR");
            throw new RegistroServiceException("Error normalizando los datos de registro", e);
        }
    }

    private void crearExpedienteSiNoExiste(ExpedientePlatino expedientePlatino) throws RegistroServiceException {
        if(!expedientePlatino.creado){
            try {
                platinoGestorDocumentalService.crearExpediente(expedientePlatino);
            }catch(Exception e){
                throw new RegistroServiceException("Error al crear el expediente en el gestor documental de platino", e);
            }
        }
    }
    
    private es.gobcan.platino.servicios.registro.Documento insertarDocumentoGestorDocumentalPlatino(
            ExpedientePlatino expedientePlatino, DatosDocumento datosDocumento, Documento documento)
            throws RegistroServiceException {
        try {
            String uri = platinoGestorDocumentalService.guardarDocumento(expedientePlatino.ruta, datosDocumento);
            es.gobcan.platino.servicios.registro.Documento doc = DatosRegistro.documentoSGRDEToRegistro(datosDocumento.getContenido(), uri);
            if (documento != null) {
            	documento.uriPlatino = uri;
            	documento.save();
            }  	
            
            return doc;
        }catch(Exception e){
            throw new RegistroServiceException("Error al insertar el documento a registrar en el gestor documental de platino", e);
        }
    }
    
    private String firmarDatosRegistro(String datosAFirmar) throws RegistroServiceException {
    	log.info("[firmarDatosRegistro] Iniciando la firma de los datos de registro");
        try {
        	log.info("Llamando a firmarTexto para firmar datosRegistro");
            String datosFirmados = firmaService.firmarTexto(datosAFirmar.getBytes("iso-8859-1"));
            log.info("[firmarDatosRegistro] Datos de registro firmados correctamente");
            return datosFirmados;
        }catch(Exception e){
            throw new RegistroServiceException("Error firmando los datos de registro", e);
        }
    }
    
    private JustificanteRegistro registroDeEntrada(String datos, String datosFirmados) throws RegistroServiceException {
        try {
        	log.info("[registroDeEntrada] Iniciando el registro de entrada");
            JustificanteRegistro justificante = registroPort.registrarEntrada(USERNAME,  PASSWORD_ENC, datos, datosFirmados, ALIAS, null, null);
            log.info("[registroDeEntrada] Registro de Entrada realizado correctamente en Platino");
            return justificante;
        }catch(Exception e){
            throw new RegistroServiceException("Error en la llamada de registro de entrada: "+ e);
        }
    }
    
    @Override
    public models.JustificanteRegistro registroDeSalida(Solicitante solicitante, Documento documento,ExpedientePlatino expediente, String descripcion) throws RegistroServiceException {
    	play.Logger.info("Preparando registro de salida");

		DatosRegistro datosRegistro = getDatosRegistro(solicitante, documento, expediente, descripcion);
		String datosAFirmar = getDatosRegistroNormalizados(expediente, datosRegistro, documento);
		play.Logger.info(datosAFirmar);

		String datosFirmados;
		try {
			datosFirmados = firmaService.firmarTexto(datosAFirmar.getBytes("iso-8859-1"));
			play.Logger.info("Datos normalizados firmados");
		} catch(Exception e){
            throw new RegistroServiceException("Error firmando los datos de registro de Salida"+ e);
        }
		
		models.JustificanteRegistro justificante;

		try {	
			JustificanteRegistro justificantePlatino = registroDeSalida(datosAFirmar, datosFirmados);
			play.Logger.info("Registro de salida realizado con justificante con NDE " + justificantePlatino.getNDE() + " Numero Registro General: " + justificantePlatino.getDatosFirmados().getNúmeroRegistro().getContent().get(0)+" Nº Registro Oficina: "+justificantePlatino.getDatosFirmados().getNúmeroRegistro().getOficina()+" / "+justificantePlatino.getDatosFirmados().getNúmeroRegistro().getNumOficina());
			play.Logger.info("registroDeSalida -> EXIT OK");
			justificante = getJustificanteRegistroModel(justificantePlatino);
			play.Logger.info("Realizando un Registro de Salida: " +
	        		"Agente: "+AgenteController.getAgente().name+
	        		"Número de Registro: "+justificante.getNumeroRegistro()+
	        		"Fecha: "+justificante.getFechaRegistro());
			play.Logger.info("Registro de Salida realizado con éxito");
		} catch (Exception e) {
			play.Logger.info("Error al obtener el justificante y EXIT "+e);
			play.Logger.info("registroDeSalida -> EXIT ERROR");
			throw new RegistroServiceException("Error al registrar de salida: "+e.getMessage());
		}
		return justificante;
	}	

	public JustificanteRegistro registroDeSalida(String datosAFirmar, String datosFirmados) throws Exception {
		// Se realiza el registro de salida, obteniendo el justificante
		String username = FapProperties.get("fap.platino.registro.username");
		String password = FapProperties.get("fap.platino.registro.password");
		String aliasServidor = FapProperties.get("fap.platino.registro.aliasServidor");

		String passwordEncrypted = PlatinoSecurityUtils.encriptarPassword(password);
		return registroPort.registrarSalida(username, passwordEncrypted, datosAFirmar, datosFirmados, aliasServidor, null);
	}
    
    private models.JustificanteRegistro getJustificanteRegistroModel(JustificanteRegistro justificantePlatino) {
    	log.info("[getJustificanteRegistroModel] Iniciando la obtencion del justificanteFap a partir del justificantePlatino");
        DateTime fechaRegistro = getRegistroDateTime(justificantePlatino);
        
        String unidadOrganica = justificantePlatino.getDatosFirmados().getNúmeroRegistro().getOficina();
        String numeroRegistro = justificantePlatino.getDatosFirmados().getNúmeroRegistro().getNumOficina().toString();
        String numeroRegistroGeneral = justificantePlatino.getDatosFirmados().getNúmeroRegistro().getContent().get(0);
        
        BinaryResponse documento = new BinaryResponse();
        documento.contenido = justificantePlatino.getReciboPdf();
        documento.nombre = "Justificante";
        models.JustificanteRegistro result = new models.JustificanteRegistro(documento, fechaRegistro, unidadOrganica, numeroRegistro, numeroRegistroGeneral);
        log.info("[getJustificanteRegistroModel] Obtención correcta del justificanteFap");
        return result;
    }
	
    /**
     * Calcula la fecha y hora a partir del justificante de platino
     * @param justificante
     * @return
     */
    private DateTime getRegistroDateTime(JustificanteRegistro justificante) {
        XMLGregorianCalendar fecha = justificante.getDatosFirmados() .getFechaRegistro();
        XMLGregorianCalendar fechaHora = justificante.getDatosFirmados().getHoraRegistro();
        DateTime dateTime = new DateTime(fecha.getYear(), fecha.getMonth(),
                fecha.getDay(), fechaHora.getHour(), fechaHora.getMinute(),
                fechaHora.getSecond(), fechaHora.getMillisecond());
        return dateTime;
    }
	
	
	/**
	 * Registra la solicitud
	 * 
	 * @throws RegistroServiceException

	public void registrarSolicitud(SolicitudGenerica solicitud) throws RegistroServiceException {
		if (!solicitud.registro.fasesRegistro.borrador) {
			Messages.error("Intentando registrar una solicitud que no se ha preparado para firmar");
			throw new RegistroServiceException(
					"Intentando registrar una solicitud que no se ha preparado para firmar");
		}

		if (!solicitud.registro.fasesRegistro.firmada) {
			Messages.error("Intentando registrar una solicitud que no ha sido firmada");
			throw new RegistroServiceException(
					"Intentando registrar una solicitud que no ha sido firmada");
		}

		// mira si se aportaron todos los documentos necesarios
		/* TODO ESTO SE DEBERíA HACER EN OTRO SITIO
		ObligatoriedadDocumentos docObli = ObligatoriedadDocumentos
				.get(ObligatoriedadDocumentos.class);
		for (Documento doc : solicitud.documentacion.documentos) {
			if (doc.tipoCiudadano != null) {
				String tipo = doc.tipoCiudadano;
				if (docObli.imprescindibles.remove(tipo))
					continue;
				else if (docObli.obligatorias.remove(tipo))
					continue;
				else if (docObli.automaticas.remove(tipo))
					continue;
			}
		}

		
		if (!docObli.imprescindibles.isEmpty()) {
			for (String uri : docObli.imprescindibles) {
				String descripcion = TableKeyValue.getValue(
						"tipoDocumentosCiudadanos", uri);
				Messages.error("Documento \"" + descripcion
						+ "\" es imprescindible");
			}
			throw new RegistroServiceException("Faltan documentos imprescindibles");
		}
		if (!docObli.obligatorias.isEmpty()) {
			for (String uri : docObli.obligatorias) {
				String descripcion = TableKeyValue.getValue(
						"tipoDocumentosCiudadanos", uri);
				Messages.warning("Documento \"" + descripcion
						+ "\" pendiente de aportación 1");
			}
		}
		if (!docObli.automaticas.isEmpty()) {
			for (String uri : docObli.automaticas) {
				if (solicitud.documentoEsObligatorio(uri)) {
					String descripcion = TableKeyValue.getValue(
							"tipoDocumentosCiudadanos", uri);
					Messages.warning("Documento \"" + descripcion
							+ "\" pendiente de aportación 2");
				}
			}
		}



		// Crea el expediente en el archivo electrónico de platino
		if (!solicitud.registro.fasesRegistro.expedientePlatino) {
			try {
				gestorDocumentalService.crearExpediente(solicitud.expedientePlatino);

				solicitud.registro.fasesRegistro.expedientePlatino = true;
				solicitud.registro.fasesRegistro.save();
			} catch (Exception e) {
				Messages.error("Error creando expediente en el gestor documental de platino");
				throw new RegistroServiceException(
						"Error creando expediente en el gestor documental de platino");
			}
		} else {
			play.Logger
					.debug("El expediente de platino para la solicitud %s ya está creado",
							solicitud.id);
		}

		// Registra la solicitud
		if (!solicitud.registro.fasesRegistro.registro) {
			try {
				DatosRegistro datos = getDatosRegistro(solicitud.solicitante,
						solicitud.registro.oficial, solicitud.expedientePlatino);
				// Registra la solicitud
				JustificanteRegistro justificante = registroDeEntrada(datos);
				play.Logger
						.info("Se ha registrado la solicitud %s en platino, solicitud.id");

				// Almacena la información de registro
				solicitud.registro.informacionRegistro
						.setDataFromJustificante(justificante);
				play.Logger
						.info("Almacenada la información del registro en la base de datos");

				// Guarda el justificante en el AED
				play.Logger
						.info("Se procede a guardar el justificante de la solicitud %s en el AED",
								solicitud.id);
				Documento documento = solicitud.registro.justificante;
				documento.tipo = FapProperties
						.get("fap.aed.tiposdocumentos.justificanteRegistroSolicitud");
				documento.descripcion = "Justificante de registro de la solicitud";
				documento.save();
				aedService.saveDocumentoTemporal(documento, justificante
						.getReciboPdf().getInputStream(),
						"JustificanteSolicitudPDF" + solicitud.id + ".pdf");

				solicitud.registro.fasesRegistro.registro = true;
				solicitud.registro.fasesRegistro.save();

				play.Logger.info("Justificante almacenado en el AED");

				// Establecemos las fechas de registro para todos los documentos
				// de la solicitud
				List<Documento> documentos = new ArrayList<Documento>();
				documentos.addAll(solicitud.documentacion.documentos);
				documentos.add(solicitud.registro.justificante);
				documentos.add(solicitud.registro.oficial);
				for (Documento doc : documentos) {
					if (doc.fechaRegistro == null) {
						doc.fechaRegistro = solicitud.registro.informacionRegistro.fechaRegistro;
					}
				}
				play.Logger.info("Fechas de registro establecidas a  "
						+ solicitud.registro.informacionRegistro.fechaRegistro);

			} catch (Exception e) {
				Messages.error("Error al registrar de entrada la solicitud");
				throw new RegistroServiceException(
						"Error al obtener el justificante del registro de entrada");
			}
		} else {
			play.Logger.debug("La solicitud %s ya está registrada",
					solicitud.id);
		}

		// Crea el expediente en el AED
		if (!solicitud.registro.fasesRegistro.expedienteAed) {
			try {
			    aedService.crearExpediente(solicitud);
			    solicitud.registro.fasesRegistro.expedienteAed = true;
			    solicitud.registro.fasesRegistro.save();
			}catch(GestorDocumentalServiceException e){
			    throw new RegistroServiceException("Error creando el expediente", e);
			}
		} else {
			play.Logger
					.debug("El expediente del aed para la solicitud %s ya está creado",
							solicitud.id);
		}

		// Cambiamos el estado de la solicitud
		if (!solicitud.estado.equals("iniciada")) {
			solicitud.estado = "iniciada";
			solicitud.save();
			Mails.enviar("solicitudIniciada", solicitud);
		}

		// Clasifica los documentos en el AED
		if (!solicitud.registro.fasesRegistro.clasificarAed) {
			boolean todosClasificados = true;
		    
		    // Clasifica los documentos sin registro
			List<Documento> documentos = new ArrayList<Documento>();
			documentos.addAll(solicitud.documentacion.documentos);
			documentos.add(solicitud.registro.justificante);
			
			try {
			    aedService.clasificarDocumentos(solicitud, documentos);
			}catch(GestorDocumentalServiceException e){
			    todosClasificados = false;
			}

			// Clasifica los documentos con registro de entrada
			List<Documento> documentosRegistrados = new ArrayList<Documento>();
			documentosRegistrados.add(solicitud.registro.oficial);
			
			try {
			    aedService.clasificarDocumentos(solicitud,documentosRegistrados,solicitud.registro.informacionRegistro);
			}catch(GestorDocumentalServiceException e){
			    todosClasificados = false;
			} 

			if (todosClasificados) {
				solicitud.registro.fasesRegistro.clasificarAed = true;
				solicitud.registro.fasesRegistro.save();
			} else {
				Messages.error("Algunos documentos no se pudieron clasificar correctamente");
			}
		} else {
			play.Logger.debug("Ya están clasificados todos los documentos de la solicitud %s",solicitud.id);
		}
	}
*/
}
