package resolucion;

import java.io.File;

import javax.inject.Inject;

import org.joda.time.DateTime;

import play.modules.guice.InjectSupport;
import play.mvc.results.RenderBinary;
import properties.FapProperties;

import reports.Report;
import services.GestorDocumentalService;

import enumerado.fap.gen.EstadoLineaResolucionEnum;
import enumerado.fap.gen.EstadoResolucionEnum;
import enumerado.fap.gen.EstadoTipoMultipleEnum;
import enumerado.fap.gen.EstadosSolicitudEnum;
import enumerado.fap.gen.ModalidadResolucionEnum;
import messages.Messages;
import models.Documento;
import models.LineaResolucionFAP;
import models.Registro;
import models.ResolucionFAP;
import models.SolicitudGenerica;

@InjectSupport
public class ResolucionBase {

	@Inject
    public static GestorDocumentalService gestorDocumentalService;
	
	private final static String HEADER_REPORT = "reports/header.html";
	private final static String FOOTER_REPORT = "reports/footer-borrador.html";
	private final static String BODY_REPORT = "reports/resolucion/resolucion.html";
	private final static String TIPO_RESOLUCION = FapProperties.get("fap.aed.tiposdocumentos.resolucion");
	public ResolucionFAP resolucion;
	
	public ResolucionBase (ResolucionFAP resolucion) {
		this.resolucion = resolucion;
	}
	
	public static String getHeaderReport() {
		return ResolucionBase.HEADER_REPORT;
	}
	
	public static String getFooterReport() {
		return ResolucionBase.FOOTER_REPORT;
	}
	
	public String getBodyReport() {
		return ResolucionBase.BODY_REPORT;
	}
	
	public String getTipoRegistroResolucion() {
		return ResolucionBase.TIPO_RESOLUCION;
	}
	
	public boolean hasAnexoConcedido() {
		return true;
	}
	
	public boolean hasAnexoDenegado() {
		return true;
	}
	
	public boolean hasAnexoExcluido() {
		return true;
	}
	
	/**
	 * Inicializamos la resolución, una vez que sabemos el tipo
	 */
	public void initResolucion(Long idResolucion) {
		ResolucionFAP resolucion = ResolucionFAP.findById(idResolucion);
		resolucion.firmarJefeServicio = true;
		resolucion.firmarDirector = true;
		resolucion.permitirPortafirma = true;
		resolucion.permitirRegistrar = true;
		resolucion.permitirPublicar = true;
		resolucion.estado = EstadoResolucionEnum.borrador.name();
		resolucion.save();
	}
	
	/**
	 * Devuelve las solicitudes "posibles" a resolver (lista desde donde se seleccionará)
	 * @return
	 */
	public static java.util.List<SolicitudGenerica> getSolicitudesAResolver (Long idResolucion) {
		return SolicitudGenerica.find("select solicitud from SolicitudGenerica solicitud where solicitud.estado in('verificado','excluido','desistido')").fetch();
	}
	
	/**
	 * Establece las líneas de resolución a una resolución, en caso de que no existan.
	 * @param idResolucion
	 */
	public void setLineasDeResolucion(Long idResolucion) {
	}
	
	/**
	 * Validación al inicio de la resolución
	 * @param idResolLong
	 */
	public static void validaInicioResolucion (Long idResolucion) {
		
	}
	
	/**
	 * Comprueba que las líneas de resolución estén en un estado "válido"
	 * @param idResolucion
	 */
	public static void validarLineasResolucion (Long idResolucion) {
		ResolucionFAP resolucion = ResolucionFAP.findById(idResolucion);
		if (resolucion.estado.equals(EstadoResolucionEnum.borrador.name())) {
			for (LineaResolucionFAP lResolucion : resolucion.lineasResolucion) {
				// TODO
			}
		}
	}
	
	/**
	 * Prepara la resolución para ser firmada
	 * 1. Valida
	 * 2. Elimina el borrador y oficial anterior
	 * 3. Genera y almacena los nuevos borrador y oficial
	 * 
	 * @param idResolucion
	 */
	public void prepararResolucion(Long idResolucion){
		ResolucionFAP resolucion = ResolucionFAP.findById(idResolucion);
        
        validar();
        eliminarBorradorResolucion(resolucion);
        eliminarOficialResolucion(resolucion);
        File borrador = generarBorradorResolucion(resolucion);
        File oficial = generarOficialResolucion(resolucion);
        almacenarEnGestorDocumentalResolucion(resolucion, borrador, oficial);    
        avanzarFase_Creada(resolucion);
    }
	
	/**
	 * Sobreescribir si queremos realizar alguna validación en la resolución antes de preparar.
	 * 
	 */
	public void validar() {

	}
	
	 public void eliminarBorradorResolucion(ResolucionFAP resolucion){
		if (!Messages.hasErrors()) {
			// Borramos los documentos que se pudieron generar en una llamada
			// previa al metodo, para no dejar basura en la BBDD
			if ((resolucion.registro.borrador != null) && (resolucion.registro.borrador.uri != null)) {
				Documento borradorOld = resolucion.registro.borrador;
				resolucion.registro.borrador = null;
				resolucion.registro.save();
				try {
					gestorDocumentalService.deleteDocumento(borradorOld);
				} catch (Exception e) {
					play.Logger.error("Error eliminando borrador del gestor documental: " + e.getMessage());
				}
			}
		}
	}
	 
	 public void eliminarOficialResolucion(ResolucionFAP resolucion) {
		if (!Messages.hasErrors()) {
			if ((resolucion.registro.oficial != null)
					&& (resolucion.registro.oficial.uri != null)) {
				Documento oficialOld = resolucion.registro.oficial;
				resolucion.registro.oficial = null;
				resolucion.registro.save();
				try {
					gestorDocumentalService.deleteDocumento(oficialOld);
				} catch (Exception e) {
					play.Logger.error("Error eliminando documento oficial del gestor documental: " + e.getMessage());
				}
			}
		}
	 }
	 
	 public File generarBorradorResolucion(ResolucionFAP resolucion) {
		File borrador = null;
		borrador = new File(this.getBodyReport());
		if (!Messages.hasErrors()) {
			try {
				play.classloading.enhancers.LocalvariablesNamesEnhancer.LocalVariablesNamesTracer.addVariable("resolucion", resolucion);
				borrador = new Report(this.getBodyReport())
						.header(this.getHeaderReport())
						.footer(this.getFooterReport())
						.renderTmpFile(resolucion);
				
				resolucion.registro.borrador = new Documento();
				resolucion.registro.borrador.tipo = getTipoRegistroResolucion();
				resolucion.registro.save();
			} catch (Exception ex2) {
				Messages.error("Error generando el documento borrador");
				play.Logger.error("Error generando el documento borrador: " + ex2.getMessage());
			}
		}

		return borrador;
	 }
	
	 public File generarOficialResolucion(ResolucionFAP resolucion){
		File oficial = null;
		if (!Messages.hasErrors()) {
			try {
				play.classloading.enhancers.LocalvariablesNamesEnhancer.LocalVariablesNamesTracer.addVariable("resolucion", resolucion);
				oficial = new Report(this.getBodyReport()).header(this.getHeaderReport()).registroSize().renderTmpFile(resolucion);
				resolucion.registro.oficial = new Documento();
				resolucion.registro.oficial.tipo = getTipoRegistroResolucion();
				resolucion.registro.save();
			} catch (Exception ex2) {
				Messages.error("Error generando el documento oficial");
				play.Logger.error("Error generando el documento oficial: " + ex2.getMessage());
			}
		}
		return oficial;
	 }
	 
	 public void almacenarEnGestorDocumentalResolucion(ResolucionFAP resolucion, File borrador, File oficial){
		if (!Messages.hasErrors()) {
			try {
				gestorDocumentalService.saveDocumentoTemporal(resolucion.registro.borrador, borrador);
				gestorDocumentalService.saveDocumentoTemporal(resolucion.registro.oficial, oficial);
			} catch (Exception e) {
				Messages.error("Error almacenando documentos en el aed");
				play.Logger.error("Error almacenando documentos en el aed: " + e.getMessage());
			}
		}
	 }
	 
	public static void avanzarFase_Borrador(ResolucionFAP resolucion) {
		if (!Messages.hasErrors()) {
			resolucion.estado = EstadoResolucionEnum.creada.name();
			resolucion.save();
		}
	}
	
	public static void avanzarFase_Creada(ResolucionFAP resolucion) {
		if (!Messages.hasErrors()) {
			if (resolucion.firmarJefeServicio) {
				resolucion.estado = EstadoResolucionEnum.preparada.name();
			}
			else if (!resolucion.firmarJefeServicio) {
				if (resolucion.firmarDirector) {
					resolucion.estado = EstadoResolucionEnum.firmadaJefeServicio.name();
				}
				else if (!resolucion.firmarDirector) {
					resolucion.estado = EstadoResolucionEnum.firmada.name();
				}
			}
			resolucion.save();
		}
	}
	
	public static void avanzarFase_Preparada(ResolucionFAP resolucion) {
		if (!Messages.hasErrors()) {
			resolucion.estado = EstadoResolucionEnum.pendienteFirmaJefeServicio.name();
			resolucion.save();
		}
	}
	
	public static void avanzarFase_Preparada_FirmaJefeServicio(ResolucionFAP resolucion) {
		if (!Messages.hasErrors()) {
			resolucion.estado = EstadoResolucionEnum.firmadaJefeServicio.name();
			resolucion.save();
		}
	}
	
	public static void avanzarFase_Preparada_Portafirma(ResolucionFAP resolucion) {
		if (!Messages.hasErrors()) {
			resolucion.estado = EstadoResolucionEnum.pendienteFirmaDirector.name();
			resolucion.save();
		}
	}
	
	public static void avanzarFase_PendienteFirmarJefeServicio(ResolucionFAP resolucion) {
		if (!Messages.hasErrors()) {
			if (resolucion.firmarDirector) {
				resolucion.estado = EstadoResolucionEnum.firmadaJefeServicio.name();
			}
			else {
				resolucion.estado = EstadoResolucionEnum.firmada.name();
			}
			resolucion.save();
		}
	}
	
	public static void avanzarFase_FirmadaJefeServicio(ResolucionFAP resolucion) {
		if (!Messages.hasErrors()) {
			resolucion.estado = EstadoResolucionEnum.pendienteFirmaDirector.name();
			resolucion.save();
		}
	}
	
	public static void avanzarFase_PendienteFirmarDirector(ResolucionFAP resolucion) {
		if (!Messages.hasErrors()) {
			resolucion.estado = EstadoResolucionEnum.firmada.name();
			resolucion.save();
		}
	}
	
	public static void avanzarFase_Firmada(ResolucionFAP resolucion) {
		if (!Messages.hasErrors()) {
			resolucion.estado = EstadoResolucionEnum.registrada.name();
			resolucion.save();
		}
	}
	
	public static void avanzarFase_Registrada(ResolucionFAP resolucion) {
		if (!Messages.hasErrors()) {
			resolucion.estado = EstadoResolucionEnum.publicada.name();
			resolucion.save();
		}
	}
	
	public static void avanzarFase_Publicada(ResolucionFAP resolucion) {
		if (!Messages.hasErrors()) {
			resolucion.estado = EstadoResolucionEnum.finalizada.name();
			resolucion.save();
		}
	}
	
	
}